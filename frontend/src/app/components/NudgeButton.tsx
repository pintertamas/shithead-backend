import { useCallback, useEffect, useRef, useState } from "react";
import { playFart } from "../lib/fartSound";
import "../styles/nudge.css";

/** Client-side cooldown after a nudge is sent. The server does not rate limit nudges separately. */
export const NUDGE_COOLDOWN_MS = 3000;
/** How long the "<username> farted" banner stays on screen. */
const NUDGE_BANNER_MS = 2000;

type Props = {
  /** Sends the nudge over the live socket. Returns false when the socket is not open. */
  onNudge: () => boolean;
};

/** Poop button placed next to the current player's own name. Pressing it plays the sound for everyone. */
export default function NudgeButton({ onNudge }: Props) {
  const [cooling, setCooling] = useState(false);
  const timer = useRef<number | null>(null);

  useEffect(() => () => {
    if (timer.current !== null) window.clearTimeout(timer.current);
  }, []);

  const press = () => {
    if (cooling) return;
    if (!onNudge()) return;
    setCooling(true);
    timer.current = window.setTimeout(() => {
      timer.current = null;
      setCooling(false);
    }, NUDGE_COOLDOWN_MS);
  };

  return (
    <button
      type="button"
      className={`nudge-button${cooling ? " is-cooling" : ""}`}
      onClick={press}
      disabled={cooling}
      aria-label="Send a fart"
      title="Nudge everyone"
    >
      <span aria-hidden="true">💩</span>
    </button>
  );
}

/**
 * Tracks the latest nudge received from another player. Plays the sound and keeps the name for about two
 * seconds, so the banner can show it.
 */
export function useNudgeNotice(): [string | null, (username: string) => void] {
  const [username, setUsername] = useState<string | null>(null);
  const timer = useRef<number | null>(null);

  useEffect(() => () => {
    if (timer.current !== null) window.clearTimeout(timer.current);
  }, []);

  const showNudge = useCallback((name: string) => {
    void playFart();
    if (timer.current !== null) window.clearTimeout(timer.current);
    setUsername(name);
    timer.current = window.setTimeout(() => {
      timer.current = null;
      setUsername(null);
    }, NUDGE_BANNER_MS);
  }, []);

  return [username, showNudge];
}

/** Short banner for a received nudge. The wrapper is always rendered so screen readers get the announcement. */
export function NudgeBanner({ username }: { username: string | null }) {
  return (
    <div className="nudge-live" role="status" aria-live="polite">
      {username && <div className="nudge-banner">{username} farted 💨</div>}
    </div>
  );
}
