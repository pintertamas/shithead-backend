import type { Participant, RemoteParticipant, RemoteTrack, RemoteTrackPublication, Room } from "livekit-client";

/*
 * Thin wrapper around livekit-client. The library is large, so it is loaded with a dynamic import() the first
 * time a player joins voice and stays out of the main bundle.
 */

export type VoiceStatus = "connecting" | "connected" | "reconnecting" | "failed";

export type VoiceSession = {
  identity: string;
  micEnabled: boolean;
  setMicrophoneEnabled: (enabled: boolean) => Promise<boolean>;
  disconnect: () => Promise<void>;
};

type Handlers = {
  onStatus: (status: VoiceStatus) => void;
  onSpeakers: (identities: string[]) => void;
};

/** Microphone constraints requested on join. */
const MIC_OPTIONS = { echoCancellation: true, noiseSuppression: true, autoGainControl: true };

export async function connectVoice(
  url: string,
  token: string,
  audioHost: HTMLElement,
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
    audioHost.appendChild(element);
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
