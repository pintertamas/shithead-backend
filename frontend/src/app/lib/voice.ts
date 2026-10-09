import { useEffect, useSyncExternalStore } from "react";
import type { Participant, RemoteParticipant, RemoteTrack, RemoteTrackPublication, Room } from "livekit-client";
import { fetchVoiceToken } from "../api/voice";
import { ApiError } from "../api/client";
import { onAuthCleared } from "../auth/authStore";

/*
 * Voice chat connection, kept at module level so it survives route changes (room -> table) and the phone chat sheet
 * closing. Components only subscribe to the snapshot and call the actions below. livekit-client is loaded with a
 * dynamic import() the first time a player joins, so it stays out of the main bundle.
 */

export type VoiceStatus = "connecting" | "connected" | "reconnecting" | "failed";

export type VoiceSession = {
  identity: string;
  micEnabled: boolean;
  setMicrophoneEnabled: (enabled: boolean) => Promise<boolean>;
  disconnect: () => Promise<void>;
};

export type VoiceSnapshot = {
  /** The game the state belongs to. Kept after leaving so a notice can still be shown on that screen. */
  sessionId: string | null;
  /** null when not in a call. */
  status: VoiceStatus | null;
  joining: boolean;
  micOn: boolean;
  micBusy: boolean;
  speakers: string[];
  localIdentity: string | null;
  notice: string | null;
};

type Handlers = {
  onStatus: (status: VoiceStatus) => void;
  onSpeakers: (identities: string[]) => void;
};

/** Leave voice after the page has been hidden this long, so idle tabs do not use up free minutes. */
export const VOICE_HIDDEN_LIMIT_MS = 5 * 60 * 1000;

const UNAVAILABLE_TEXT = "Voice chat is not available right now.";
const FINISHED_TEXT = "Voice chat closed because the game has ended.";
const HIDDEN_TEXT = "Voice left because this tab was hidden for 5 minutes.";
const MIC_DENIED_TEXT = "Couldn't turn the microphone on. Check the browser permission and try again.";

/** Microphone constraints requested on join. */
const MIC_OPTIONS = { echoCancellation: true, noiseSuppression: true, autoGainControl: true };

const IDLE: VoiceSnapshot = {
  sessionId: null,
  status: null,
  joining: false,
  micOn: false,
  micBusy: false,
  speakers: [],
  localIdentity: null,
  notice: null
};

let snapshot: VoiceSnapshot = IDLE;
let session: VoiceSession | null = null;
/** Incremented on every join and disconnect; stale async work compares against it and bails out. */
let attempt = 0;
let hiddenTimer: number | null = null;
let audioHost: HTMLDivElement | null = null;
const listeners = new Set<() => void>();

function setSnapshot(patch: Partial<VoiceSnapshot>) {
  snapshot = { ...snapshot, ...patch };
  listeners.forEach((listener) => listener());
}

/** Remote audio elements live under one hidden element that is created once and never removed. */
function getAudioHost(): HTMLElement {
  if (!audioHost) {
    audioHost = document.createElement("div");
    audioHost.hidden = true;
    audioHost.setAttribute("data-voice-audio", "");
    document.body.appendChild(audioHost);
  }
  return audioHost;
}

function clearHiddenTimer() {
  if (hiddenTimer !== null) {
    window.clearTimeout(hiddenTimer);
    hiddenTimer = null;
  }
}

/** Whether the URL is still the room or table of this game. Navigating between them keeps voice connected. */
function isOnSessionScreen(sessionId: string): boolean {
  const parts = window.location.pathname.split("/");
  const index = parts.indexOf(sessionId);
  return index > 0 && (parts[index - 1] === "room" || parts[index - 1] === "game");
}

async function connectVoice(
  url: string,
  token: string,
  audioHostElement: HTMLElement,
  handlers: Handlers
): Promise<VoiceSession> {
  const { Room: RoomClass, RoomEvent, Track, ConnectionState } = await import("livekit-client");
  const room: Room = new RoomClass({ adaptiveStream: false, dynacast: false });
  const audioElements = new Map<RemoteTrack, HTMLAudioElement>();
  let closing = false;

  room.on(RoomEvent.TrackSubscribed, (track: RemoteTrack, _publication: RemoteTrackPublication, _participant: RemoteParticipant) => {
    if (track.kind !== Track.Kind.Audio) return;
    const element = document.createElement("audio");
    element.autoplay = true;
    element.setAttribute("playsinline", "");
    audioHostElement.appendChild(element);
    track.attach(element);
    audioElements.set(track, element);
  });
  room.on(RoomEvent.TrackUnsubscribed, (track: RemoteTrack) => {
    track.detach();
    audioElements.get(track)?.remove();
    audioElements.delete(track);
  });
  room.on(RoomEvent.ActiveSpeakersChanged, (speakers: Participant[]) => {
    handlers.onSpeakers(speakers.map((speaker) => speaker.identity));
  });
  room.on(RoomEvent.Reconnecting, () => handlers.onStatus("reconnecting"));
  room.on(RoomEvent.Reconnected, () => handlers.onStatus("connected"));
  room.on(RoomEvent.Disconnected, () => {
    if (!closing) handlers.onStatus("failed");
  });

  try {
    await room.connect(url, token);
  } catch (cause) {
    closing = true;
    await room.disconnect().catch(() => undefined);
    throw cause;
  }
  handlers.onStatus(room.state === ConnectionState.Reconnecting ? "reconnecting" : "connected");
  // Browsers may block playback until a user gesture; the join click usually covers this.
  void room.startAudio().catch(() => undefined);

  let micEnabled = false;
  try {
    await room.localParticipant.setMicrophoneEnabled(true, MIC_OPTIONS);
    micEnabled = true;
  } catch {
    // Microphone denied or unavailable: stay connected to listen only.
  }

  return {
    identity: room.localParticipant.identity,
    micEnabled,
    setMicrophoneEnabled: async (enabled: boolean) => {
      try {
        await room.localParticipant.setMicrophoneEnabled(enabled, MIC_OPTIONS);
        return enabled;
      } catch {
        return room.localParticipant.isMicrophoneEnabled;
      }
    },
    disconnect: async () => {
      closing = true;
      for (const [track, element] of audioElements) {
        track.detach();
        element.remove();
      }
      audioElements.clear();
      await room.disconnect().catch(() => undefined);
    }
  };
}

/** Drops the current connection, if any. The snapshot keeps its sessionId so `notice` stays visible on that screen. */
export async function disconnectVoice(notice: string | null = null): Promise<void> {
  attempt++;
  clearHiddenTimer();
  const current = session;
  session = null;
  setSnapshot({ status: null, joining: false, micOn: false, micBusy: false, speakers: [], localIdentity: null, notice });
  if (current) await current.disconnect();
}

/** Requests a token and connects. Only called from the "Join voice" button. */
export async function joinVoice(sessionId: string, token: string): Promise<void> {
  if (snapshot.joining) return;
  if (session && snapshot.sessionId === sessionId) return;
  await disconnectVoice(null);

  const id = ++attempt;
  setSnapshot({
    sessionId,
    joining: true,
    status: "connecting",
    notice: null,
    micOn: false,
    micBusy: false,
    speakers: [],
    localIdentity: null
  });
  try {
    const voice = await fetchVoiceToken(token, sessionId);
    if (id !== attempt) return;
    const connection = await connectVoice(voice.url, voice.token, getAudioHost(), {
      onStatus: (next) => { if (id === attempt) setSnapshot({ status: next }); },
      onSpeakers: (ids) => { if (id === attempt) setSnapshot({ speakers: ids }); }
    });
    if (id !== attempt) {
      await connection.disconnect();
      return;
    }
    session = connection;
    setSnapshot({
      localIdentity: connection.identity,
      micOn: connection.micEnabled,
      status: "connected",
      notice: connection.micEnabled ? null : "Microphone unavailable. You can listen, but others cannot hear you."
    });
  } catch (cause) {
    if (id !== attempt) return;
    setSnapshot({
      status: null,
      notice: cause instanceof ApiError ? UNAVAILABLE_TEXT : "Couldn't connect to voice. Please try again."
    });
  } finally {
    if (id === attempt) setSnapshot({ joining: false });
  }
}

export async function toggleVoiceMic(): Promise<void> {
  const current = session;
  if (!current || snapshot.micBusy) return;
  const wasOn = snapshot.micOn;
  setSnapshot({ micBusy: true });
  const next = await current.setMicrophoneEnabled(!wasOn);
  if (session !== current) return;
  setSnapshot({
    micOn: next,
    micBusy: false,
    notice: !wasOn && !next ? MIC_DENIED_TEXT : snapshot.notice
  });
}

/** Ends the call when the game has finished. */
function endVoiceIfFinished(sessionId: string) {
  if (snapshot.sessionId !== sessionId) return;
  if (!session && !snapshot.joining && snapshot.status === null) return;
  void disconnectVoice(FINISHED_TEXT);
}

/** Another game's voice must not keep running once this game's screen is shown. */
function releaseOtherSessions(sessionId: string) {
  if (snapshot.sessionId && snapshot.sessionId !== sessionId && (session || snapshot.joining)) {
    void disconnectVoice(null);
  }
}

/** Leaves when the URL is no longer this game's room or table (leave button, lobby, another game). */
function leaveIfOffScreen(sessionId: string) {
  if (snapshot.sessionId !== sessionId) return;
  if (!session && !snapshot.joining) return;
  if (isOnSessionScreen(sessionId)) return;
  void disconnectVoice(null);
}

function subscribeVoice(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function getVoiceSnapshot(): VoiceSnapshot {
  return snapshot;
}

/** Voice state for one game. Reads as idle when the connection belongs to another game. */
export function useVoiceSnapshot(sessionId: string | undefined): VoiceSnapshot {
  const current = useSyncExternalStore(subscribeVoice, getVoiceSnapshot, getVoiceSnapshot);
  if (!sessionId || current.sessionId !== sessionId) return IDLE;
  return current;
}

/**
 * Mount this once per chat panel (always mounted on the room and the table). It keeps the connection's lifecycle in
 * step with the screens: it ends on finish and when the player leaves the game, and returns the game's voice state.
 */
export function useVoiceSession(sessionId: string | undefined, finished: boolean): VoiceSnapshot {
  useEffect(() => {
    if (!sessionId) return undefined;
    releaseOtherSessions(sessionId);
    return () => leaveIfOffScreen(sessionId);
  }, [sessionId]);

  useEffect(() => {
    if (sessionId && finished) endVoiceIfFinished(sessionId);
  }, [sessionId, finished]);

  return useVoiceSnapshot(sessionId);
}

// Module-level: sign-out always ends the call.
onAuthCleared(() => {
  void disconnectVoice(null);
});

if (typeof document !== "undefined") {
  // The page-hidden timer lives here, so it runs for as long as the call is up, whichever screen shows it.
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") {
      if (hiddenTimer === null && session) {
        hiddenTimer = window.setTimeout(() => {
          hiddenTimer = null;
          void disconnectVoice(HIDDEN_TEXT);
        }, VOICE_HIDDEN_LIMIT_MS);
      }
    } else {
      clearHiddenTimer();
    }
  });
}
