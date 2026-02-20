import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { createGame, joinGame } from "../api/game";
import { useAuth } from "../auth/useAuth";

export default function Lobby() {
  const navigate = useNavigate();
  const { token, username, logout } = useAuth();
  const [joinCode, setJoinCode] = useState("");
  const [status, setStatus] = useState<string | null>(null);

  const handleCreate = async () => {
    setStatus(null);
    try {
      const res = await createGame(token);
      navigate(`/room/${res.sessionId}`);
    } catch {
      setStatus("Failed to create game.");
    }
  };

  const handleJoin = async () => {
    setStatus(null);
    try {
      const trimmed = joinCode.trim().toUpperCase();
      if (!trimmed) return;
      await joinGame(token, trimmed);
      navigate(`/room/${trimmed}`);
    } catch {
      setStatus("Failed to join game.");
    }
  };

  return (
    <div className="page fade-in">
      <div className="topbar">
        <div>
          <div className="badge">Signed In</div>
          <h2 className="title">Welcome, {username || "Player"}</h2>
        </div>
        <button
          className="button secondary"
          onClick={() => {
            logout();
            navigate("/login");
          }}
        >
          Log out
        </button>
      </div>

      <div className="layout">
        <div className="glass card">
          <h3 className="title">Create a Game</h3>
          <p style={{ color: "var(--ink-dim)" }}>
            Generate a short join code and invite friends.
          </p>
          <button className="button" onClick={handleCreate}>
            Create Game
          </button>
        </div>

        <div className="glass card">
          <h3 className="title">Join a Game</h3>
          <p style={{ color: "var(--ink-dim)" }}>
            Enter a join code to hop into a lobby.
          </p>
          <input
            className="input"
            placeholder="Enter join code"
            value={joinCode}
            onChange={(e) => setJoinCode(e.target.value)}
          />
          <div style={{ height: 12 }} />
          <button className="button" onClick={handleJoin}>
            Join Game
          </button>
          {status && <p style={{ color: "var(--danger)" }}>{status}</p>}
        </div>

        <div className="glass card">
          <h3 className="title">How it works</h3>
          <p style={{ color: "var(--ink-dim)" }}>
            You’ll see your hand, face-up cards, and counts for hidden cards.
            Enemy hands remain hidden. The game table updates in real time.
          </p>
        </div>
      </div>
    </div>
  );
}

