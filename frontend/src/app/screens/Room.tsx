import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { fetchState, startGame, leaveGame, GameStateView } from "../api/game";
import { useAuth } from "../auth/useAuth";
import ErrorAlert from "../components/ErrorAlert";

export default function Room() {
  const { sessionId } = useParams();
  const navigate = useNavigate();
  const { token } = useAuth();
  const [state, setState] = useState<GameStateView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState<string | null>(null);
  const failCount = useRef(0);
  const transitionStarted = useRef(false);
  const transitionTimer = useRef<number | null>(null);

  const showStartingScreen = useCallback(() => {
    setLoading("starting");
    if (transitionStarted.current || !sessionId) return;
    transitionStarted.current = true;
    transitionTimer.current = window.setTimeout(() => {
      navigate(`/game/${sessionId}`);
    }, 500);
  }, [navigate, sessionId]);

  useEffect(() => {
    if (!sessionId) return;
    let cancelled = false;

    const refresh = async () => {
      try {
        const data = await fetchState(token, sessionId);
        if (cancelled) return;
        setState(data);
        setError(null);
        failCount.current = 0;
        if (data.started) {
          showStartingScreen();
        }
      } catch {
        if (cancelled) return;
        failCount.current++;
        if (failCount.current >= 3) {
          setError("Failed to load lobby state.");
        }
      }
    };

    refresh();
    const handle = setInterval(refresh, 1000);
    return () => {
      cancelled = true;
      clearInterval(handle);
      if (transitionTimer.current !== null) window.clearTimeout(transitionTimer.current);
    };
  }, [sessionId, token, showStartingScreen]);

  const canStart = useMemo(() => {
    if (!state) return false;
    return state.isOwner && state.players.length >= 2 && !state.started;
  }, [state]);

  const onStart = async () => {
    if (!sessionId) return;
    setLoading("starting");
    try {
      await startGame(token, sessionId);
      showStartingScreen();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Failed to start game.");
      setLoading(null);
    }
  };

  return (
    <div className="page fade-in">
      <ErrorAlert message={error} onDismiss={() => setError(null)} />
      <div className="topbar">
        <div>
          <div className="badge">Lobby</div>
          <h2 className="title">Room {sessionId}</h2>
        </div>
        <div style={{ display: "flex", gap: 8 }}>
          {canStart && (
            <button className="button" onClick={onStart} disabled={loading !== null}>
              {loading === "starting" ? "Starting..." : "Start Game"}
            </button>
          )}
          <button className="button secondary" disabled={loading !== null} onClick={async () => {
            setLoading("leaving");
            if (sessionId) {
              try { await leaveGame(token, sessionId); } catch {}
            }
            navigate("/lobby");
          }}>
            {loading === "leaving" ? "Leaving..." : "Leave"}
          </button>
        </div>
      </div>

      <div className="layout single">
        <div className="glass card">
          <h3 className="title">Players</h3>
          {!state && !error && <p style={{ color: "var(--ink-dim)" }}>Loading...</p>}
          <div className="player-list">
            {state?.players.map((player) => (
              <div key={player.playerId} className="player-item">
                <span>{player.username}</span>
                {player.isYou && <span className="badge">You</span>}
              </div>
            ))}
          </div>
        </div>
      </div>
      {loading === "starting" && (
        <div className="game-starting-overlay" role="status" aria-live="polite">
          <div className="game-starting-message">
            <div className="game-starting-spinner" aria-hidden="true" />
            <div>
              <h2 className="title">Starting game…</h2>
              <p style={{ color: "var(--ink-dim)", marginBottom: 0 }}>The owner started the game. Getting everyone to the table.</p>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
