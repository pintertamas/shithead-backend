import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { fetchVoiceToken } from "../api/voice";
import { ApiError } from "../api/client";
import { useAuth } from "../auth/useAuth";
import { connectVoice, VoiceSession, VoiceStatus } from "../lib/voice";
import Icon from "./Icon";
import "../styles/voice.css";

/** Leave voice after the page has been hidden this long, so idle tabs do not use up free minutes. */
export const VOICE_HIDDEN_LIMIT_MS = 5 * 60 * 1000;

const NOTICE_TEXT = "Voice is processed by LiveKit; nobody is recorded.";
const UNAVAILABLE_TEXT = "Voice chat is not available right now.";

type Props = {
  sessionId: string;
  players: { playerId: string; username: string }[];
  finished: boolean;
  /** Optional: receives the identities of players who are speaking, so seats can be highlighted. */
  onSpeakingChange?: (identities: string[]) => void;
};

export default function VoicePanel({ sessionId, players, finished, onSpeakingChange }: Props) {
  const { token } = useAuth();
  const [status, setStatus] = useState<VoiceStatus | null>(null);
  const [joining, setJoining] = useState(false);
  const [micOn, setMicOn] = useState(false);
  const [micBusy, setMicBusy] = useState(false);
  const [speakers, setSpeakers] = useState<string[]>([]);
  const [localIdentity, setLocalIdentity] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const sessionRef = useRef<VoiceSession | null>(null);
  const attemptRef = useRef(0);
  const audioHostRef = useRef<HTMLDivElement | null>(null);
  const hiddenTimer = useRef<number | null>(null);
  const onSpeakingChangeRef = useRef(onSpeakingChange);
  onSpeakingChangeRef.current = onSpeakingChange;

  const names = useMemo(() => new Map(players.map((player) => [player.playerId, player.username])), [players]);

  /** Drops the current connection (if any) without touching the UI state. */
  const teardown = useCallback(async () => {
    attemptRef.current++;
    const session = sessionRef.current;
    sessionRef.current = null;
    if (session) await session.disconnect();
  }, []);

  const leave = useCallback(async (message: string | null = null) => {
    await teardown();
    setJoining(false);
    setMicOn(false);
    setMicBusy(false);
    setSpeakers([]);
    setLocalIdentity(null);
    setStatus(null);
    setNotice(message);
  }, [teardown]);

  const join = async () => {
    if (joining) return;
    setJoining(true);
    setNotice(null);
    await teardown();
    const attempt = ++attemptRef.current;
    setStatus("connecting");
    try {
      const voice = await fetchVoiceToken(token, sessionId);
      if (attempt !== attemptRef.current) return;
      const host = audioHostRef.current;
      if (!host) return;
      const session = await connectVoice(voice.url, voice.token, host, {
        onStatus: (next) => { if (attempt === attemptRef.current) setStatus(next); },
        onSpeakers: (ids) => { if (attempt === attemptRef.current) setSpeakers(ids); }
      });
      if (attempt !== attemptRef.current) {
        await session.disconnect();
        return;
      }
      sessionRef.current = session;
      setLocalIdentity(session.identity);
      setMicOn(session.micEnabled);
      setStatus("connected");
      if (!session.micEnabled) {
        setNotice("Microphone unavailable. You can listen, but others cannot hear you.");
      }
    } catch (cause) {
      if (attempt !== attemptRef.current) return;
      setStatus(null);
      setNotice(cause instanceof ApiError ? UNAVAILABLE_TEXT : "Couldn't connect to voice. Please try again.");
    } finally {
      if (attempt === attemptRef.current) setJoining(false);
    }
  };

  const toggleMic = async () => {
    const session = sessionRef.current;
    if (!session || micBusy) return;
    setMicBusy(true);
    const next = await session.setMicrophoneEnabled(!micOn);
    setMicOn(next);
    setMicBusy(false);
    if (!micOn && !next) {
      setNotice("Couldn't turn the microphone on. Check the browser permission and try again.");
    }
  };

  // Tell the parent who is speaking (for seat highlights) without re-running on every render.
  useEffect(() => {
    onSpeakingChangeRef.current?.(speakers);
  }, [speakers]);

  // Disconnect when the component unmounts (leaving the table or the room).
  useEffect(() => () => { void teardown(); }, [teardown]);

  // Disconnect when the game ends.
  useEffect(() => {
    if (finished && (sessionRef.current || status !== null)) {
      void leave("Voice chat closed because the game has ended.");
    }
  }, [finished, status, leave]);

  // Disconnect when the page stays hidden for too long.
  useEffect(() => {
    const onVisibilityChange = () => {
      if (document.visibilityState === "hidden") {
        if (hiddenTimer.current === null && sessionRef.current) {
          hiddenTimer.current = window.setTimeout(() => {
            hiddenTimer.current = null;
            void leave("Voice left because this tab was hidden for 5 minutes.");
          }, VOICE_HIDDEN_LIMIT_MS);
        }
      } else if (hiddenTimer.current !== null) {
        window.clearTimeout(hiddenTimer.current);
        hiddenTimer.current = null;
      }
    };
    document.addEventListener("visibilitychange", onVisibilityChange);
    return () => {
      document.removeEventListener("visibilitychange", onVisibilityChange);
      if (hiddenTimer.current !== null) window.clearTimeout(hiddenTimer.current);
      hiddenTimer.current = null;
    };
  }, [leave]);

  const inCall = status === "connecting" || status === "connected" || status === "reconnecting";
  const speakerNames = speakers.map((id) => (id === localIdentity ? "You" : names.get(id) ?? "Someone"));

  return (
    <section className="voice-panel" aria-label="Voice chat">
      <div className="voice-controls">
        {!inCall && !finished && (
          <button className="voice-button" type="button" onClick={join} disabled={joining}>
            <Icon name="mic" size={18} />
            {joining ? "Connecting..." : "Join voice"}
          </button>
        )}
        {inCall && (
          <>
            <button
              className={`voice-icon-button${micOn ? "" : " is-muted"}`}
              type="button"
              aria-pressed={micOn}
              aria-label={micOn ? "Mute microphone" : "Unmute microphone"}
              title={micOn ? "Mute microphone" : "Unmute microphone"}
              onClick={toggleMic}
              disabled={micBusy || status === "connecting"}
            >
              <Icon name={micOn ? "mic" : "mic-off"} size={20} />
            </button>
            <button className="voice-button secondary" type="button" onClick={() => void leave(null)}>
              Leave voice
            </button>
          </>
        )}
      </div>
      <p className="voice-notice">{NOTICE_TEXT}</p>
      {status && (
        <p className={`voice-status is-${status}`} role="status">{STATUS_TEXT[status]}</p>
      )}
      {speakerNames.length > 0 && (
        <p className="voice-speaking" aria-live="polite">
          <span className="voice-speaking-dot" aria-hidden="true" />
          Speaking: {speakerNames.join(", ")}
        </p>
      )}
      {notice && <p className="voice-error" role="alert">{notice}</p>}
      <div ref={audioHostRef} hidden />
    </section>
  );
}

const STATUS_TEXT: Record<VoiceStatus, string> = {
  connecting: "Connecting to voice...",
  connected: "Voice connected",
  reconnecting: "Reconnecting...",
  failed: "Voice disconnected. Leave and join again to reconnect."
};
