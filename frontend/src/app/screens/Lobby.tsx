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
    <div className="page fade-in lobby-page">
      <aside className="lobby-sidebar" aria-label="Main navigation">
        <div className="lobby-brand" aria-label="Shithead home">🎮</div>
        <nav className="lobby-nav">
          <button className="lobby-nav-item active" aria-current="page"><span aria-hidden="true">⌂</span>Home</button>
          <button className="lobby-nav-item" onClick={() => navigate("/config")}><span aria-hidden="true">⚙</span>Game Config</button>
          <button className="lobby-nav-item" onClick={() => navigate("/profile")}><span aria-hidden="true">♙</span>Profile</button>
          <button className="lobby-nav-item" onClick={() => { logout(); navigate("/login"); }}><span aria-hidden="true">↪</span>Log out</button>
        </nav>
      </aside>

      <main className="lobby-main">
        <ErrorAlert message={status} onDismiss={() => setStatus(null)} />
        <header className="lobby-welcome">
          <h1>Welcome, {displayName || "Player"} <span aria-hidden="true">👋</span></h1>
          <p>Ready to play? Create a new game or join an existing one.</p>
        </header>

        <section className="lobby-action-grid" aria-label="Play a game">
          <article className="lobby-action-card">
            <div className="lobby-action-icon" aria-hidden="true">🎮</div>
            <h2>Create Game</h2>
            <p>Start a new game with your saved configuration.</p>
            <button className="button lobby-cta" onClick={handleCreate} disabled={loading !== null}>
              <span aria-hidden="true">＋</span>{loading === "creating" ? "Creating…" : "Create Game"}
            </button>
            <div className="lobby-info"><span aria-hidden="true">ⓘ</span> New games use your saved configuration.</div>
          </article>

          <article className="lobby-action-card">
            <div className="lobby-action-icon" aria-hidden="true">👥</div>
            <h2>Join Game</h2>
            <p>Enter the game code shared by the owner.</p>
            <form className="lobby-join-form" onSubmit={(event) => { event.preventDefault(); void handleJoin(); }}>
              <input
                className="input lobby-code-input"
                aria-label="Game join code"
                placeholder="Enter game code"
                maxLength={6}
                autoCapitalize="characters"
                autoComplete="off"
                value={joinCode}
                onChange={(event) => setJoinCode(event.target.value.toUpperCase())}
                disabled={loading !== null}
              />
              <button className="button lobby-cta" type="submit" disabled={loading !== null || !joinCode.trim()}>
                <span aria-hidden="true">↪</span>{loading === "joining" ? "Joining…" : "Join Game"}
              </button>
            </form>
          </article>
        </section>

        <section className="lobby-rankings" aria-labelledby="lobby-rankings-title">
          <div className="lobby-rankings-heading">
            <div className="lobby-rankings-title-wrap">
              <span className="lobby-trophy" aria-hidden="true">🏆</span>
              <div>
                <h2 id="lobby-rankings-title">Rankings</h2>
                <p>See the best players and their current ELO.</p>
              </div>
            </div>
            <button className="button secondary lobby-rankings-link" onClick={() => navigate("/leaderboard")}>
              <span aria-hidden="true">▮</span> Full leaderboard
            </button>
          </div>

          {leaderboardLoading ? <div className="lobby-rankings-message" role="status"><span className="game-starting-spinner" />Loading rankings…</div>
            : leaders.length === 0 ? <p className="lobby-rankings-message">Leaderboard is unavailable right now.</p>
              : (
                <div className="lobby-table-wrap">
                  <table className="lobby-rankings-table">
                    <thead><tr><th scope="col">#</th><th scope="col">Name</th><th scope="col">ELO</th></tr></thead>
                    <tbody>
                      {leaders.map((entry, index) => (
                        <tr key={entry.userId}>
                          <td><span className={`lobby-rank${index < 3 ? ` top-${index + 1}` : ""}`}>{index + 1}</span></td>
                          <td>{entry.username}</td>
                          <td>{Math.round(entry.eloScore)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
        </section>
      </main>
    </div>
  );
}
