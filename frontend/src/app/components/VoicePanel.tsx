import { useMemo } from "react";
import { useAuth } from "../auth/useAuth";
import { disconnectVoice, joinVoice, toggleVoiceMic, useVoiceSnapshot, VoiceStatus } from "../lib/voice";
import Icon from "./Icon";
import "../styles/voice.css";

const NOTICE_TEXT = "Audio runs through LiveKit. Nothing is recorded.";

type Props = {
  sessionId: string;
  players: { playerId: string; username: string }[];
  finished: boolean;
};

/** Voice controls shown inside the chat. The connection itself lives in lib/voice.ts and outlives this component. */
export default function VoicePanel({ sessionId, players, finished }: Props) {
  const { token } = useAuth();
  const voice = useVoiceSnapshot(sessionId);
  const { status, joining, micOn, micBusy, speakers, localIdentity, notice } = voice;

  const names = useMemo(() => new Map(players.map((player) => [player.playerId, player.username])), [players]);

  const inCall = status === "connecting" || status === "connected" || status === "reconnecting";
  const speakerNames = speakers.map((id) => (id === localIdentity ? "You" : names.get(id) ?? "Someone"));

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
      {!inCall && !finished && <p className="voice-notice">{NOTICE_TEXT}</p>}
      {speakerNames.length > 0 && (
        <p className="voice-speaking" aria-live="polite">
          <span className="voice-speaking-dot" aria-hidden="true" />
          Speaking: {speakerNames.join(", ")}
        </p>
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
