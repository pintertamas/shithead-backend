import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ApiError } from "../api/client";
import { rematch } from "../api/game";
import { startVoiceHandoff } from "../lib/voice";
import "../styles/results-panel.css";

export const REMATCH_WINDOW_MS = 60_000;
const REMATCH_STARTED_MESSAGE = "The game has already started.";

type Props = {
  sessionId: string;
  token: string | null | undefined;
  shitheadName: string;
  youAreShithead: boolean;
  finishedAt?: number | null;
  rematchSessionId?: string | null;
  rematchStarted?: boolean;
};

/** Milliseconds left in the rematch window, clamped to [0, REMATCH_WINDOW_MS]. */
export function rematchRemainingMs(now: number, anchor: number): number {
  return Math.min(REMATCH_WINDOW_MS, Math.max(0, REMATCH_WINDOW_MS - (now - anchor)));
}

/** Server finish time, or the moment this client first saw the game finished when it is missing or in the future (clock skew). */
function useRematchRemaining(finishedAt?: number | null): number {
  const firstSeen = useRef(Date.now());
  const anchor = typeof finishedAt === "number" && finishedAt <= Date.now() ? finishedAt : firstSeen.current;
  const [remaining, setRemaining] = useState(() => rematchRemainingMs(Date.now(), anchor));
  useEffect(() => {
    const tick = () => setRemaining(rematchRemainingMs(Date.now(), anchor));
    tick();
    const handle = window.setInterval(tick, 500);
    return () => window.clearInterval(handle);
  }, [anchor]);
  return remaining;
}

function formatClock(ms: number) {
  const total = Math.ceil(ms / 1000);
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, "0")}`;
}

export default function ResultsPanel({
  sessionId, token, shitheadName, youAreShithead, finishedAt, rematchSessionId, rematchStarted
}: Props) {
  const navigate = useNavigate();
  const [open, setOpen] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const remaining = useRematchRemaining(finishedAt);
  const windowOpen = remaining > 0;
  const panelRef = useRef<HTMLElement>(null);
  const pillRef = useRef<HTMLButtonElement>(null);
  const moveFocus = useRef(false);

  // Move focus between the panel and the pill when the user toggles, never on first render.
  useEffect(() => {
    if (!moveFocus.current) return;
    moveFocus.current = false;
    (open ? panelRef.current : pillRef.current)?.focus();
  }, [open]);

  // Showing or hiding the panel also clears any inline alert, so it never lingers.
  const toggle = useCallback((next: boolean) => { moveFocus.current = true; setNotice(null); setOpen(next); }, []);

  const playAgain = async () => {
    if (rematchStarted) { setNotice(REMATCH_STARTED_MESSAGE); return; }
    if (busy || !token) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    // Must run synchronously in the click: it captures the current voice call before navigation tears it down.
    const handoff = startVoiceHandoff(sessionId);
    try {
      const { sessionId: newId } = await rematch(token, sessionId);
      try {
        await handoff?.(newId, token);
      } catch {
        // Voice is best effort; the rematch lobby still opens.
      }
      navigate(`/room/${newId}`);
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 409 && /rematch already started/i.test(cause.message)) {
        setNotice(REMATCH_STARTED_MESSAGE);
      } else {
        setError(cause instanceof Error ? cause.message : "Couldn't start a rematch.");
      }
      setBusy(false);
    }
  };

  const joinable = windowOpen && !rematchStarted;
  const playLabel = busy ? "Opening..." : rematchSessionId ? "Join rematch" : "Play again";

  if (!open) {
    return (
      <button ref={pillRef} type="button" className="results-pill" aria-label="Show results" aria-expanded="false"
        aria-controls="results-panel" onClick={() => toggle(true)}>
        <span className="results-pill-dot" aria-hidden="true" />
        Results
        {joinable && <span className="results-pill-time" aria-hidden="true">{formatClock(remaining)}</span>}
      </button>
    );
  }

  return (
    <section ref={panelRef} id="results-panel" className="results-panel glass" tabIndex={-1} aria-labelledby="results-title"
      onKeyDown={(e) => { if (e.key === "Escape") toggle(false); }}>
      <button type="button" className="results-close" aria-label="Hide results" onClick={() => toggle(false)}>
        <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" focusable="false">
          <path d="M5 12h14" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" fill="none" />
        </svg>
      </button>
      <div className="badge">Game over</div>
      <h2 id="results-title" className="title results-title">
        <span className="results-label">Shithead</span>
        <span className="results-name">{shitheadName}{youAreShithead ? " (you)" : ""}</span>
      </h2>

      {joinable ? (
        <div className="results-countdown" role="timer" aria-live="off">
          <span className="results-time">{formatClock(remaining)}</span>
          <span className="results-hint">
            {rematchSessionId ? "Rematch lobby is open" : "left to start a rematch"}
          </span>
          <span className="results-bar" aria-hidden="true">
            <span className="results-bar-fill" style={{ transform: `scaleX(${remaining / REMATCH_WINDOW_MS})` }} />
          </span>
        </div>
      ) : windowOpen ? null : (
        <p className="results-note">Time to head to the leaderboard.</p>
      )}

      {error && <p className="results-error" role="alert">{error}</p>}
      {notice && <p className="results-alert" role="alert">{notice}</p>}

      <div className="results-actions">
        {windowOpen && (
          <button type="button" className="button" aria-disabled={rematchStarted ? "true" : undefined}
            disabled={!rematchStarted && (busy || !token)} onClick={() => void playAgain()}>
            {playLabel}
          </button>
        )}
        <button type="button" className={windowOpen ? "button secondary" : "button"}
          onClick={() => navigate(`/leaderboard/${sessionId}`)}>
          View leaderboard
        </button>
      </div>
    </section>
  );
}
