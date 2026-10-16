import { useEffect, useRef, useSyncExternalStore } from "react";
import type { Participant, RemoteParticipant, RemoteTrack, RemoteTrackPublication, Room } from "livekit-client";
import { fetchVoiceToken } from "../api/voice";
import type { VoiceToken } from "../api/voice";
import { ApiError } from "../api/client";
import { onAuthCleared } from "../auth/authStore";

/*
 * Voice chat connection, kept at module level so it survives route changes (room -> table) and the phone chat sheet
 * closing. Components only subscribe to the snapshot and call the actions below. livekit-client is loaded with a
 * dynamic import() the first time a player joins, so it stays out of the main bundle.
 */

export type VoiceStatus = "connecting" | "connected" | "reconnecting" | "failed";

export type VoiceParticipant = {
  identity: string;
  name?: string;
  speaking: boolean;
  micOn: boolean;
};

export type VoiceSession = {
  identity: string;
  micEnabled: boolean;
  micError: string | null;
  setMicrophoneEnabled: (enabled: boolean) => Promise<{ enabled: boolean; error: string | null }>;
  /** Must be called synchronously from a click handler. */
  enableAudio: () => Promise<void>;
  disconnect: () => Promise<void>;
};

export type VoiceSnapshot = {
  /** The game the state belongs to. Kept after leaving so a notice can still be shown on that screen. */
  sessionId: string | null;
  /** The LiveKit room (a game id) actually joined. Differs from sessionId after switching into a rematch room. */
  roomId: string | null;
  /** Epoch ms when the post-game timer will end the call; null when no such timer is armed. */
  endsAt: number | null;
  /** null when not in a call. */
  status: VoiceStatus | null;
  joining: boolean;
  micOn: boolean;
  micBusy: boolean;
  /** Identities of everyone (including you) the server reports as speaking. */
  speakers: string[];
  localIdentity: string | null;
  /** Remote participants only. */
  participants: VoiceParticipant[];
  /** True when the browser blocks remote audio playback until a user gesture. */
  audioBlocked: boolean;
  notice: string | null;
};

type Handlers = {
  onStatus: (status: VoiceStatus) => void;
  onSpeakers: (identities: string[]) => void;
  onParticipants: (participants: VoiceParticipant[]) => void;
  onAudioBlocked: (blocked: boolean) => void;
};

/** Thrown by connectVoice with a short, token-free reason for the notice. */
class VoiceConnectError extends Error {
  constructor(public readonly reason: string) {
    super(reason);
    this.name = "VoiceConnectError";
  }
}

/** Leave voice after the page has been hidden this long, so idle tabs do not use up free minutes. */
export const VOICE_HIDDEN_LIMIT_MS = 5 * 60 * 1000;
/** Leave voice after being alone in the room this long. */
export const VOICE_ALONE_LIMIT_MS = 3 * 60 * 1000;
/** Players may keep talking this long after the game ends (the caller derives leaveAtMs from it). */
export const VOICE_AFTER_GAME_MS = 60_000;
/** Hard cap on one connection, so a forgotten call cannot run all day. */
export const VOICE_CONNECTED_CAP_MS = 90 * 60 * 1000;

const UNAVAILABLE_TEXT = "Voice chat is not available right now.";
const FINISHED_TEXT = "Voice chat closed because the game has ended.";
const HIDDEN_TEXT = "Voice left because this tab was hidden for 5 minutes.";
const ALONE_TEXT = "Left voice because nobody else was in it.";
const CAP_TEXT = "Voice left after 90 minutes so the free allowance lasts.";
const MIC_DENIED_TEXT = "Microphone permission was denied. Allow the microphone for this site in your browser settings, then turn it on again.";
const MIC_NOT_FOUND_TEXT = "No microphone was found on this device.";
const MIC_FAILED_TEXT = "Couldn't turn the microphone on. Try again.";

/** Microphone constraints requested on join. */
const MIC_OPTIONS = { echoCancellation: true, noiseSuppression: true, autoGainControl: true };

const IDLE: VoiceSnapshot = {
  sessionId: null,
  roomId: null,
  endsAt: null,
  status: null,
  joining: false,
  micOn: false,
  micBusy: false,
  speakers: [],
  localIdentity: null,
  participants: [],
  audioBlocked: false,
  notice: null
};

let snapshot: VoiceSnapshot = IDLE;
let session: VoiceSession | null = null;
/** Incremented on every join and disconnect; stale async work compares against it and bails out. */
let attempt = 0;
/** True while switchVoiceRoom is running, so a second switch cannot start. */
let switching = false;
let hiddenTimer: number | null = null;
let aloneTimer: number | null = null;
let capTimer: number | null = null;
let audioHost: HTMLDivElement | null = null;
let sharedAudioContext: AudioContext | null = null;
const listeners = new Set<() => void>();

function setSnapshot(patch: Partial<VoiceSnapshot>) {
  snapshot = { ...snapshot, ...patch };
  listeners.forEach((listener) => listener());
}

/**
 * Runs inside a user gesture: creates or resumes a shared AudioContext and plays one silent sample. Once a page has
 * started audio from a gesture, browsers (notably iOS Safari) let later playback start without another tap.
 */
function unlockAudioInGesture() {
  try {
    const Ctor = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!Ctor) return;
    if (!sharedAudioContext) sharedAudioContext = new Ctor();
    void sharedAudioContext.resume?.().catch(() => undefined);
    const source = sharedAudioContext.createBufferSource();
    source.buffer = sharedAudioContext.createBuffer(1, 1, 22050);
    source.connect(sharedAudioContext.destination);
    source.start(0);
  } catch {
    // Web Audio unavailable: the "Tap to enable sound" button remains the fallback.
  }
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

function clearAloneTimer() {
  if (aloneTimer !== null) {
    window.clearTimeout(aloneTimer);
    aloneTimer = null;
  }
}

function clearCapTimer() {
  if (capTimer !== null) {
    window.clearTimeout(capTimer);
    capTimer = null;
  }
}

/** Starts the alone countdown while connected with nobody else in the room; clears it as soon as someone joins. */
function updateAloneTimer() {
  if (!session || snapshot.status !== "connected" || snapshot.participants.length > 0) {
    clearAloneTimer();
    return;
  }
  if (aloneTimer !== null) return;
  aloneTimer = window.setTimeout(() => {
    aloneTimer = null;
    console.info("[voice] auto-leave: alone in the room");
    void disconnectVoice(ALONE_TEXT);
  }, VOICE_ALONE_LIMIT_MS);
}

/** Whether the URL is still the room or table of this game. Navigating between them keeps voice connected. */
function isOnSessionScreen(sessionId: string): boolean {
  const parts = window.location.pathname.split("/");
  const index = parts.indexOf(sessionId);
  return index > 0 && (parts[index - 1] === "room" || parts[index - 1] === "game");
}

function micErrorText(cause: unknown): string {
  const name = typeof cause === "object" && cause !== null && "name" in cause ? String((cause as { name: unknown }).name) : "";
  if (name === "NotAllowedError" || name === "SecurityError") return MIC_DENIED_TEXT;
  if (name === "NotFoundError" || name === "OverconstrainedError") return MIC_NOT_FOUND_TEXT;
  return MIC_FAILED_TEXT;
}

async function connectVoice(
  url: string,
  token: string,
  audioHostElement: HTMLElement,
  handlers: Handlers,
  startMuted = false
): Promise<VoiceSession> {
  const { Room: RoomClass, RoomEvent, Track, ConnectionState, ConnectionError, ConnectionErrorReason } = await import("livekit-client");
  const room: Room = new RoomClass({ adaptiveStream: false, dynacast: false });
  const audioElements = new Map<RemoteTrack, HTMLAudioElement>();
  let closing = false;
  let speakerIds: string[] = [];

  const publishParticipants = () => {
    const list: VoiceParticipant[] = [];
    room.remoteParticipants.forEach((participant: RemoteParticipant) => {
      list.push({
        identity: participant.identity,
        name: participant.name || undefined,
        speaking: speakerIds.includes(participant.identity),
        micOn: participant.isMicrophoneEnabled
      });
    });
    handlers.onParticipants(list);
  };
  const checkAudio = () => handlers.onAudioBlocked(!room.canPlaybackAudio);
  const startAudio = (): Promise<void> => room.startAudio().catch(() => undefined).then(checkAudio);

  room.on(RoomEvent.TrackSubscribed, (track: RemoteTrack, _publication: RemoteTrackPublication, _participant: RemoteParticipant) => {
    if (track.kind !== Track.Kind.Audio) return;
    const element = document.createElement("audio");
    element.autoplay = true;
    element.setAttribute("playsinline", "");
    element.volume = 1;
    element.muted = false;
    audioHostElement.appendChild(element);
    track.attach(element);
    audioElements.set(track, element);
    void element.play().then(checkAudio, () => handlers.onAudioBlocked(true));
    publishParticipants();
  });
  room.on(RoomEvent.TrackUnsubscribed, (track: RemoteTrack) => {
    track.detach();
    audioElements.get(track)?.remove();
    audioElements.delete(track);
    publishParticipants();
  });
  room.on(RoomEvent.AudioPlaybackStatusChanged, checkAudio);
  room.on(RoomEvent.ActiveSpeakersChanged, (speakers: Participant[]) => {
    speakerIds = speakers.map((speaker) => speaker.identity);
    handlers.onSpeakers(speakerIds);
    publishParticipants();
  });
  const onMembersChanged = () => publishParticipants();
  room.on(RoomEvent.ParticipantConnected, onMembersChanged);
  room.on(RoomEvent.ParticipantDisconnected, onMembersChanged);
  room.on(RoomEvent.TrackPublished, onMembersChanged);
  room.on(RoomEvent.TrackUnpublished, onMembersChanged);
  room.on(RoomEvent.TrackMuted, onMembersChanged);
  room.on(RoomEvent.TrackUnmuted, onMembersChanged);
  room.on(RoomEvent.Reconnecting, () => handlers.onStatus("reconnecting"));
  room.on(RoomEvent.Reconnected, () => handlers.onStatus("connected"));
  room.on(RoomEvent.Disconnected, () => {
    if (closing) return;
    handlers.onParticipants([]);
    handlers.onStatus("failed");
  });

  try {
    await room.connect(url, token);
  } catch (cause) {
    closing = true;
    await room.disconnect().catch(() => undefined);
    // Short, token-free reason only. The URL and token are never included.
    let reason = "unknown error";
    if (cause instanceof ConnectionError) {
      if (cause.reason === ConnectionErrorReason.NotAllowed) reason = "token rejected";
      else if (
        cause.reason === ConnectionErrorReason.ServerUnreachable ||
        cause.reason === ConnectionErrorReason.WebSocket ||
        cause.reason === ConnectionErrorReason.Timeout
      ) {
        reason = "could not reach LiveKit";
      }
    }
    throw new VoiceConnectError(reason);
  }
  handlers.onStatus(room.state === ConnectionState.Reconnecting ? "reconnecting" : "connected");
  publishParticipants();
  // Outside a gesture this usually stays blocked; it is retried by enableAudio() from the button or the join click.
  void startAudio();

  let micEnabled = false;
  let micError: string | null = null;
  try {
    if (!startMuted) {
      await room.localParticipant.setMicrophoneEnabled(true, MIC_OPTIONS);
      micEnabled = true;
    }
  } catch (cause) {
    // Microphone denied or unavailable: stay connected to listen only.
    micError = micErrorText(cause);
  }

  return {
    identity: room.localParticipant.identity,
    micEnabled,
    micError,
    setMicrophoneEnabled: async (enabled: boolean) => {
      try {
        await room.localParticipant.setMicrophoneEnabled(enabled, MIC_OPTIONS);
        return { enabled, error: null };
      } catch (cause) {
        return { enabled: room.localParticipant.isMicrophoneEnabled, error: micErrorText(cause) };
      }
    },
    enableAudio: () => {
      // Called synchronously from a click: starts playback before any await.
      for (const element of audioElements.values()) void element.play().catch(() => undefined);
      return startAudio();
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
  clearAloneTimer();
  clearCapTimer();
  const current = session;
  session = null;
  if (current || snapshot.status !== null || snapshot.joining) console.info("[voice] left");
  switching = false;
  setSnapshot({
    roomId: null,
    endsAt: null,
    status: null,
    joining: false,
    micOn: false,
    micBusy: false,
    speakers: [],
    localIdentity: null,
    participants: [],
    audioBlocked: false,
    notice
  });
  if (current) await current.disconnect();
}

/** Remote audio was blocked: call from a click handler on the "Tap to enable sound" button. */
export function enableVoiceAudio(): void {
  unlockAudioInGesture();
  const current = session;
  if (!current) return;
  void current.enableAudio();
}

function joinFailureText(cause: unknown): string {
  if (cause instanceof VoiceConnectError) return `Couldn't connect to voice: ${cause.reason}. Please try again.`;
  if (cause instanceof ApiError) {
    return cause.status === 401 || cause.status === 403
      ? "Couldn't connect to voice: token rejected. Please try again."
      : UNAVAILABLE_TEXT;
  }
  return "Couldn't connect to voice: unknown error. Please try again.";
}

/** Fetches a token for roomId and connects; on success stores the session and arms the timers. Throws on failure. */
async function establishConnection(
  id: number,
  roomId: string,
  token: string,
  startMuted: boolean,
  prefetched?: VoiceToken
): Promise<boolean> {
  const voice = prefetched ?? (await fetchVoiceToken(token, roomId));
  if (id !== attempt) return false;
  console.info("[voice] token received, connecting to LiveKit");
  const connection = await connectVoice(voice.url, voice.token, getAudioHost(), {
    onStatus: (next) => {
      if (id !== attempt) return;
      console.info(`[voice] status: ${next}`);
      setSnapshot({ status: next });
    },
    onSpeakers: (ids) => { if (id === attempt) setSnapshot({ speakers: ids }); },
    onParticipants: (list) => {
      if (id !== attempt) return;
      console.info(`[voice] remote participants: ${list.length}`);
      setSnapshot({ participants: list });
      updateAloneTimer();
    },
    onAudioBlocked: (blocked) => {
      if (id !== attempt || snapshot.audioBlocked === blocked) return;
      console.info(`[voice] audio blocked: ${blocked}`);
      setSnapshot({ audioBlocked: blocked });
    }
  }, startMuted);
  if (id !== attempt) {
    await connection.disconnect();
    return false;
  }
  session = connection;
  setSnapshot({
    localIdentity: connection.identity,
    micOn: connection.micEnabled,
    status: "connected",
    notice: connection.micError ? `${connection.micError} Others cannot hear you until it is on.` : null
  });
  console.info(`[voice] connected, microphone ${connection.micEnabled ? "on" : "off"}`);
  clearCapTimer();
  capTimer = window.setTimeout(() => {
    capTimer = null;
    console.info("[voice] auto-leave: connection cap reached");
    void disconnectVoice(CAP_TEXT);
  }, VOICE_CONNECTED_CAP_MS);
  updateAloneTimer();
  return true;
}

/**
 * Requests a token and connects. Only called from the "Join voice" button. `roomId` is the LiveKit room (a game id)
 * and defaults to sessionId, the screen the call belongs to.
 */
export async function joinVoice(
  sessionId: string,
  token: string,
  options: { startMuted?: boolean; roomId?: string } = {}
): Promise<void> {
  // Runs synchronously inside the click, before the first await.
  unlockAudioInGesture();
  if (snapshot.joining) return;
  if (session && snapshot.sessionId === sessionId) return;
  await disconnectVoice(null);

  const roomId = options.roomId ?? sessionId;
  const id = ++attempt;
  console.info("[voice] joining");
  setSnapshot({
    sessionId,
    roomId,
    endsAt: null,
    joining: true,
    status: "connecting",
    notice: null,
    micOn: false,
    micBusy: false,
    speakers: [],
    localIdentity: null,
    participants: [],
    audioBlocked: false
  });
  try {
    await establishConnection(id, roomId, token, Boolean(options.startMuted));
  } catch (cause) {
    if (id !== attempt) return;
    const notice = joinFailureText(cause);
    console.info(`[voice] join failed: ${notice}`);
    setSnapshot({ status: null, notice });
  } finally {
    if (id === attempt) setSnapshot({ joining: false });
  }
}

/**
 * Moves the running call for sessionId into another LiveKit room (the rematch room), keeping the mic state and the
 * screen key. The token is fetched first, so if that fails the old call simply continues. Does nothing when not in a
 * call for sessionId or already in roomId.
 */
export async function switchVoiceRoom(sessionId: string, roomId: string, token: string): Promise<void> {
  if (switching || snapshot.joining || !session) return;
  if (snapshot.sessionId !== sessionId || snapshot.roomId === roomId) return;
  const startAttempt = attempt;
  const micWasOn = snapshot.micOn;
  switching = true;
  try {
    let voice;
    try {
      voice = await fetchVoiceToken(token, roomId);
    } catch {
      console.info("[voice] room switch skipped: token request failed");
      return;
    }
    if (startAttempt !== attempt || !session || snapshot.sessionId !== sessionId) return;
    console.info("[voice] switching room");
    const old = session;
    session = null;
    const id = ++attempt;
    clearAloneTimer();
    setSnapshot({
      roomId,
      joining: true,
      status: "connecting",
      micOn: false,
      micBusy: false,
      speakers: [],
      localIdentity: null,
      participants: [],
      audioBlocked: false
    });
    await old.disconnect();
    if (id !== attempt) return;
    try {
      await establishConnection(id, roomId, token, !micWasOn, voice);
    } catch (cause) {
      if (id !== attempt) return;
      const notice = joinFailureText(cause);
      console.info(`[voice] room switch failed: ${notice}`);
      setSnapshot({ status: null, roomId: null, endsAt: null, notice });
    } finally {
      if (id === attempt) setSnapshot({ joining: false });
    }
  } finally {
    switching = false;
  }
}

/** Shows (or clears, with null) the post-game countdown for the call of sessionId. */
export function setVoiceEndsAt(sessionId: string, endsAt: number | null): void {
  if (snapshot.sessionId !== sessionId || snapshot.endsAt === endsAt) return;
  if (endsAt !== null && !session && !snapshot.joining) return;
  setSnapshot({ endsAt });
}

export async function toggleVoiceMic(): Promise<void> {
  const current = session;
  if (!current || snapshot.micBusy) return;
  const wasOn = snapshot.micOn;
  setSnapshot({ micBusy: true });
  const result = await current.setMicrophoneEnabled(!wasOn);
  if (session !== current) return;
  setSnapshot({
    micOn: result.enabled,
    micBusy: false,
    notice: result.error ?? (result.enabled ? null : snapshot.notice)
  });
}

/** Ends the call for this game (finished). */
function endVoiceFor(sessionId: string, notice: string) {
  if (snapshot.sessionId !== sessionId) return;
  if (!session && !snapshot.joining && snapshot.status === null) return;
  void disconnectVoice(notice);
}

/** Session whose call is being handed to a rematch room; its screens must not end the call meanwhile. */
let handoffFrom: string | null = null;
let handoffTimer: number | null = null;

function clearHandoff() {
  handoffFrom = null;
  if (handoffTimer !== null) {
    window.clearTimeout(handoffTimer);
    handoffTimer = null;
  }
}

/**
 * Call synchronously inside the "Play again" click. Returns null when not in a call for oldSessionId. Otherwise the
 * returned function (call it once the rematch API resolved) leaves the old room and joins the new one with the mic
 * in the state it had, so the chat seems to continue.
 */
export function startVoiceHandoff(oldSessionId: string): ((newSessionId: string, token: string) => Promise<void>) | null {
  const inCall = snapshot.status === "connecting" || snapshot.status === "connected" || snapshot.status === "reconnecting";
  if (snapshot.sessionId !== oldSessionId || !inCall) return null;
  unlockAudioInGesture();
  const micWasOn = snapshot.status === "connecting" ? true : snapshot.micOn;
  clearHandoff();
  handoffFrom = oldSessionId;
  // If the rematch call fails and the function is never used, stop shielding the old call after a while.
  handoffTimer = window.setTimeout(clearHandoff, 30_000);
  let used = false;
  return async (newSessionId: string, token: string) => {
    if (used) return;
    used = true;
    try {
      if (snapshot.sessionId !== oldSessionId) return; // the call already ended (left, timed out)
      await disconnectVoice(null);
      await joinVoice(newSessionId, token, { startMuted: !micWasOn });
    } finally {
      if (handoffFrom === oldSessionId) clearHandoff();
    }
  };
}

/** Another game's voice must not keep running once this game's screen is shown. */
function releaseOtherSessions(sessionId: string) {
  if (snapshot.sessionId && snapshot.sessionId !== sessionId && snapshot.sessionId !== handoffFrom && (session || snapshot.joining)) {
    void disconnectVoice(null);
  }
}

/** Leaves when the URL is no longer this game's room or table (leave button, lobby, another game). */
function leaveIfOffScreen(sessionId: string) {
  if (snapshot.sessionId !== sessionId || sessionId === handoffFrom) return;
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
export function useVoiceSession(
  sessionId: string | undefined,
  finished: boolean,
  leaveAtMs?: number | null,
  rematchStarted?: boolean,
  rematchSessionId?: string | null,
  token?: string | null
): VoiceSnapshot {
  const voice = useVoiceSnapshot(sessionId);
  const inCall = voice.status === "connected" || voice.status === "reconnecting";
  const switchTried = useRef<string | null>(null);

  useEffect(() => {
    if (!sessionId) return undefined;
    releaseOtherSessions(sessionId);
    return () => leaveIfOffScreen(sessionId);
  }, [sessionId]);

  // After the game: stay until leaveAtMs, or leave at once without a (future) deadline.
  useEffect(() => {
    if (!sessionId || !finished) return undefined;
    const delay = leaveAtMs == null ? 0 : leaveAtMs - Date.now();
    if (delay <= 0) {
      endVoiceFor(sessionId, FINISHED_TEXT);
      return undefined;
    }
    setVoiceEndsAt(sessionId, leaveAtMs ?? null);
    const timer = window.setTimeout(() => endVoiceFor(sessionId, FINISHED_TEXT), delay);
    return () => window.clearTimeout(timer);
  }, [sessionId, finished, leaveAtMs, inCall]);

  // A rematch started without this viewer: move the call into the rematch room until the timer above ends it.
  useEffect(() => {
    if (!sessionId || !finished || !rematchStarted || !rematchSessionId || !token || !inCall) return;
    if (leaveAtMs == null || leaveAtMs <= Date.now()) return;
    const key = `${sessionId}:${rematchSessionId}`;
    if (switchTried.current === key) return;
    switchTried.current = key;
    void switchVoiceRoom(sessionId, rematchSessionId, token);
  }, [sessionId, finished, rematchStarted, rematchSessionId, token, inCall, leaveAtMs]);

  return voice;
}

// Module-level: sign-out always ends the call.
onAuthCleared(() => {
  void disconnectVoice(null);
});

if (typeof window !== "undefined") {
  // Closing or reloading the page must not leave the player connected (free minutes are billed per connection).
  const leaveOnPageExit = () => {
    if (session || snapshot.joining) void disconnectVoice(null);
  };
  window.addEventListener("pagehide", leaveOnPageExit);
  window.addEventListener("beforeunload", leaveOnPageExit);
}

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
