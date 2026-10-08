import { useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { createGame, joinGame } from "../api/game";
import { useAuth } from "../auth/useAuth";
import ErrorAlert from "../components/ErrorAlert";
import { fetchProfile } from "../api/profile";
import { getCreateGameConfig, loadGameConfig } from "../config/gameConfig";
import { fetchGlobalLeaderboard, LeaderboardEntry } from "../api/leaderboard";

export default function Lobby() {
  const navigate = useNavigate();
  const location = useLocation();
  const { token, logout } = useAuth();
  const [joinCode, setJoinCode] = useState("");
  const [status, setStatus] = useState<string | null>(null);
  const [loading, setLoading] = useState<string | null>(null);
  const [displayName, setDisplayName] = useState("");
  const [leaders, setLeaders] = useState<LeaderboardEntry[]>([]);
  const [leaderboardLoading, setLeaderboardLoading] = useState(true);

  useEffect(() => {
    const navigationError = (location.state as { error?: unknown } | null)?.error;
    if (typeof navigationError === "string") {
      setStatus(navigationError);
      navigate(location.pathname, { replace: true, state: null });
    }
  }, [location.pathname, location.state, navigate]);

  useEffect(() => {
    fetchProfile(token).then((profile) => setDisplayName(profile.username)).catch(() => undefined);
  }, [token]);

  useEffect(() => {
    let active = true;
    fetchGlobalLeaderboard(token, 10)
      .then((entries) => { if (active) setLeaders(entries); })
      .catch(() => { if (active) setLeaders([]); })
      .finally(() => { if (active) setLeaderboardLoading(false); });
    return () => { active = false; };
  }, [token]);

  const handleCreate = async () => {
    setStatus(null);
    setLoading("creating");
    try {
      const res = await createGame(token, getCreateGameConfig(loadGameConfig()));
      navigate(`/room/${res.sessionId}`);
    } catch (cause) {
      setStatus(cause instanceof Error ? cause.message : "Failed to create game.");
    } finally {
      setLoading(null);
    }
  };

  const handleJoin = async () => {
    setStatus(null);
    const trimmed = joinCode.trim().toUpperCase();
    if (!trimmed) return;
    setLoading("joining");
    try {
      await joinGame(token, trimmed);
      navigate(`/room/${trimmed}`);
    } catch (cause) {
      setStatus(cause instanceof Error ? cause.message : "Failed to join game.");
    } finally {
      setLoading(null);
    }
  };

  return (
    <div className="page fade-in">
      <ErrorAlert message={status} onDismiss={() => setStatus(null)} />
      <div className="topbar">
        <div>
          <div className="badge">Signed In</div>
          <h2 className="title">Welcome, {displayName || "Player"}</h2>
        </div>
        <div className="topbar-actions">
          <button className="button secondary" onClick={() => navigate("/config")}>Game Config</button>
          <button className="button secondary" onClick={() => navigate("/profile")}>Profile</button>
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

      <div className="layout lobby-layout">
        <div className="glass card">
          <h3 className="title">Create a Game</h3>
          <p style={{ color: "var(--ink-dim)" }}>
            Generate a short join code and invite friends.
          </p>
          <p className="config-note">New games use your saved configuration.</p>
          <button className="button" onClick={handleCreate} disabled={loading !== null}>
            {loading === "creating" ? "Creating..." : "Create Game"}
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
            disabled={loading !== null}
          />
          <div style={{ height: 12 }} />
          <button className="button" onClick={handleJoin} disabled={loading !== null}>
            {loading === "joining" ? "Joining..." : "Join Game"}
          </button>
        </div>

        <div className="glass card leaderboard-card">
          <div className="leaderboard-card-heading">
            <div><div className="badge">Rankings</div><h3 className="title">Top players</h3></div>
            <button className="button secondary" onClick={() => navigate("/leaderboard")}>Full leaderboard</button>
          </div>
          {leaderboardLoading ? <p className="config-note">Loading leaderboard…</p> : leaders.length === 0 ? <p className="config-note">Leaderboard is unavailable right now.</p> : (
            <div className="player-list">
              {leaders.map((entry, index) => <div className="player-item" key={entry.userId}><span>{index + 1}. {entry.username}</span><strong>{Math.round(entry.eloScore)}</strong></div>)}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
