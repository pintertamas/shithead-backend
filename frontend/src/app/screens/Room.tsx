import { useEffect, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { fetchState, startGame, GameStateView } from "../api/game";
import { useAuth } from "../auth/useAuth";

export default function Room() {
  const { sessionId } = useParams();
  const navigate = useNavigate();
  const { token } = useAuth();
  const [state, setState] = useState<GameStateView | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = async () => {
    if (!sessionId) return;
    try {
      const data = await fetchState(token, sessionId);
      setState(data);
      if (data.started) {
        navigate(`/game/${sessionId}`);
      }
    } catch {
      setError("Failed to load lobby state.");
    }
  };

  useEffect(() => {
    refresh();
    const handle = setInterval(refresh, 3000);
    return () => clearInterval(handle);
  }, [sessionId]);

  const canStart = useMemo(() => {
    if (!state) return false;
    return state.isOwner && state.players.length >= 2 && !state.started;
  }, [state]);

  const onStart = async () => {
    if (!sessionId) return;
    try {
      await startGame(token, sessionId);
      refresh();
    } catch {
      setError("Failed to start game.");
    }
  };

  return (
    <div className="page fade-in">
      <div className="topbar">
        <div>
          <div className="badge">Lobby</div>
          <h2 className="title">Room {sessionId}</h2>
        </div>
        {canStart && (
          <button className="button" onClick={onStart}>
            Start Game
          </button>
        )}
      </div>

      <div className="layout single">
        <div className="glass card">
          <h3 className="title">Players</h3>
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

