import { useEffect, useMemo, useState } from "react";
import { useAuth } from "../auth/useAuth";
import { disconnectVoice, enableVoiceAudio, joinVoice, toggleVoiceMic, useVoiceSnapshot, VoiceStatus } from "../lib/voice";
import Icon from "./Icon";
import "../styles/voice.css";

const NOTICE_TEXT = "Audio runs through LiveKit. Nothing is recorded.";
const FREE_ALLOWANCE_TEXT = "Voice uses the free monthly allowance while you are connected; leave when you are done.";

const WARN_MS = 10_000;

function formatCountdown(ms: number): string {
  const total = Math.max(0, Math.ceil(ms / 1000));
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, "0")}`;
}

/** Live "Voice closes in M:SS" line. The visible text ticks every second; the spoken label changes every 10 s. */
function VoiceCountdown({ endsAt }: { endsAt: number }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    setNow(Date.now());
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [endsAt]);
  const left = Math.max(0, endsAt - now);
  const spokenSeconds = Math.ceil(left / 10_000) * 10;
  const spoken = `Voice closes in about ${spokenSeconds >= 60 ? `${Math.floor(spokenSeconds / 60)} minute${spokenSeconds >= 120 ? "s" : ""}${spokenSeconds % 60 ? ` ${spokenSeconds % 60} seconds` : ""}` : `${spokenSeconds} seconds`}`;
  return (
    <p className={`voice-notice voice-after-game voice-countdown${left <= WARN_MS ? " is-warning" : ""}`} role="timer" aria-label={spoken}>
      <span aria-hidden="true">Voice closes in <span className="voice-countdown-time">{formatCountdown(left)}</span></span>
    </p>
  );
}

type Props = {
  sessionId: string;
  players: { playerId: string; username: string }[];
  finished: boolean;
};

/** Voice controls shown inside the chat. The connection itself lives in lib/voice.ts and outlives this component. */
export default function VoicePanel({ sessionId, players, finished }: Props) {
  const { token } = useAuth();
  const voice = useVoiceSnapshot(sessionId);
  const { status, joining, micOn, micBusy, speakers, localIdentity, participants, audioBlocked, notice, endsAt } = voice;

  const names = useMemo(() => new Map(players.map((player) => [player.playerId, player.username])), [players]);

  const inCall = status === "connecting" || status === "connected" || status === "reconnecting";
  const localSpeaking = localIdentity !== null && speakers.includes(localIdentity);
  const others = participants.map((participant) => ({
    identity: participant.identity,
    label: names.get(participant.identity) ?? participant.name ?? "Someone",
    speaking: participant.speaking,
    micOn: participant.micOn
  }));

  return (
    <section className="voice-panel" aria-label="Voice chat">
      <div className="voice-controls">
        {!inCall && !finished && (
          <button className="voice-button" type="button" onClick={() => void joinVoice(sessionId, token)} disabled={joining}>
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
              onClick={() => void toggleVoiceMic()}
              disabled={micBusy || status === "connecting"}
            >
              <Icon name={micOn ? "mic" : "mic-off"} size={20} />
            </button>
            <button className="voice-button secondary" type="button" onClick={() => void disconnectVoice(null)}>
              Leave voice
            </button>
          </>
        )}
        {status && <p className={`voice-status is-${status}`} role="status">{STATUS_TEXT[status]}</p>}
      </div>
      {!inCall && !finished && (
        <>
          <p className="voice-notice">{NOTICE_TEXT}</p>
          <p className="voice-notice voice-allowance">{FREE_ALLOWANCE_TEXT}</p>
        </>
      )}
      {finished && inCall && endsAt !== null && <VoiceCountdown endsAt={endsAt} />}
      {finished && inCall && endsAt === null && <p className="voice-notice voice-after-game">Voice stays open for a minute after the game.</p>}
      {audioBlocked && inCall && (
        <div className="voice-unlock">
          <p className="voice-error">Your browser blocked voice audio. Tap to hear the table.</p>
          <button className="voice-button" type="button" onClick={() => enableVoiceAudio()}>
            <Icon name="mic" size={18} />
            Tap to enable sound
          </button>
        </div>
      )}
      {status === "connected" && (
        <div className="voice-roster" aria-live="polite">
          <p className="voice-roster-summary">
            In voice: you{others.length > 0 ? ` + ${others.length} ${others.length === 1 ? "other" : "others"}` : ""}
          </p>
          {localSpeaking && (
            <p className="voice-person is-speaking">
              <span className="voice-speaking-dot" aria-hidden="true" />
              You are speaking
            </p>
          )}
          {others.length === 0 && <p className="voice-notice">Nobody else has joined voice yet.</p>}
          {others.length > 0 && (
            <ul className="voice-people">
              {others.map((person) => (
                <li key={person.identity} className={`voice-person${person.speaking ? " is-speaking" : ""}`}>
                  <span className="voice-speaking-dot" aria-hidden="true" />
                  <span>{person.label}</span>
                  {!person.micOn && <span className="voice-person-muted">(muted)</span>}
                  {person.speaking && <span className="voice-sr-only"> is speaking</span>}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
      {notice && <p className="voice-error" role="alert">{notice}</p>}
    </section>
  );
}

const STATUS_TEXT: Record<VoiceStatus, string> = {
  connecting: "Connecting to voice...",
  connected: "Voice connected",
  reconnecting: "Reconnecting...",
  failed: "Voice disconnected. Leave and join again to reconnect."
};
