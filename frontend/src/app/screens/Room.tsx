import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { fetchState, startGame, leaveGame, GameStateView } from "../api/game";
import { useAuth } from "../auth/useAuth";

export default function Room() {
  const { sessionId } = useParams();
  const navigate = useNavigate();
  const { token } = useAuth();
  const [state, setState] = useState<GameStateView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState<string | null>(null);
  const failCount = useRef(0);

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
          navigate(`/game/${sessionId}`);
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
    const handle = setInterval(refresh, 3000);
    return () => {
      cancelled = true;
      clearInterval(handle);
    };
  }, [sessionId, token]);

  const canStart = useMemo(() => {
    if (!state) return false;
    return state.isOwner && state.players.length >= 2 && !state.started;
  }, [state]);

  const onStart = async () => {
    if (!sessionId) return;
    setLoading("starting");
    try {
      await startGame(token, sessionId);
    } catch {
      setError("Failed to start game.");
    } finally {
      setLoading(null);
    }
  };

  return (
    <div className="page fade-in">
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
          {error && <p style={{ color: "var(--danger)" }}>{error}</p>}
        </div>
      </div>
    </div>
  );
}
