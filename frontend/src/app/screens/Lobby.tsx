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
        <div className="lobby-brand" aria-label="Shithead card king logo">
          <svg viewBox="0 0 64 64" role="img" aria-label="A king with a poop crown on a playing card">
            <rect x="4" y="3" width="56" height="58" rx="10" fill="#183527" stroke="#e7d8a4" strokeWidth="1.5" />
            <rect x="8" y="7" width="48" height="50" rx="7" fill="none" stroke="#6f8b68" strokeWidth=".8" />
            <text x="12" y="19" fill="#f4e8c2" fontSize="10" fontWeight="700" fontFamily="serif">K</text>
            <path d="M11 21h5" stroke="#d7c58b" strokeWidth="1" />
            <path d="M16 24 13 27l3 3 3-3z" fill="#d7c58b" />
            <path d="M21 35c0-8 5-13 11-13s11 5 11 13v8H21z" fill="#d8c89d" stroke="#f2e5bd" strokeWidth="1" />
            <path d="M21 32c1-8 5-13 11-13s10 5 11 13l-4-4-3 3-4-4-4 4-3-3z" fill="#6a3f2e" stroke="#c39b67" strokeWidth="1" />
            <path d="M26 34h2m8 0h2" stroke="#243c2f" strokeWidth="1.6" strokeLinecap="round" />
            <path d="M27 40c2-3 8-3 10 0-1 3-3 4-5 4s-4-1-5-4z" fill="#4a3027" />
            <path d="M19 45h26l7 8H12z" fill="#31543d" stroke="#e7d8a4" strokeWidth="1" />
            <path d="m21 46 4 5m18-5-4 5M24 48h16" stroke="#d7c58b" strokeWidth="1" />
            <path d="m22 25-3-8 8 3 5-7 5 7 8-3-3 8z" fill="#d2aa4e" stroke="#f0d78c" strokeWidth=".9" strokeLinejoin="round" />
            <path d="M22 25h20v3H22z" fill="#b88d38" stroke="#f0d78c" strokeWidth=".8" />
            <path d="M28 18c-2-2-.5-5 2-5 0-3 4-4 5-1 3 0 4 3 2 5-2 3-7 3-9 1z" fill="#684330" stroke="#d0a46b" strokeWidth=".9" />
            <path d="M31 16c1-1 2-2 4-1" fill="none" stroke="#e4bf83" strokeWidth=".8" strokeLinecap="round" />
          </svg>
        </div>
        <nav className="lobby-nav">
          <button className="lobby-nav-item active" aria-current="page" aria-label="Home" title="Home"><span className="lobby-nav-icon" aria-hidden="true">🏠</span><span className="lobby-nav-label">Home</span></button>
          <button className="lobby-nav-item" onClick={() => navigate("/config")} aria-label="Game Config" title="Game Config"><span className="lobby-nav-icon" aria-hidden="true">⚙️</span><span className="lobby-nav-label">Game Config</span></button>
          <button className="lobby-nav-item" onClick={() => navigate("/profile")} aria-label="Profile" title="Profile"><span className="lobby-nav-icon" aria-hidden="true">👤</span><span className="lobby-nav-label">Profile</span></button>
          <button className="lobby-nav-item" onClick={() => { logout(); navigate("/login"); }} aria-label="Log out" title="Log out"><span className="lobby-nav-icon" aria-hidden="true">🚪</span><span className="lobby-nav-label">Log out</span></button>
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
            <div className="lobby-action-icon" aria-hidden="true">
              <svg viewBox="0 0 48 48">
                <rect x="8" y="8" width="27" height="34" rx="4" transform="rotate(-9 8 8)" fill="#264b36" stroke="#d7c58b" strokeWidth="1.5" />
                <path d="M14 12h12M12 17h13" stroke="#9caf8c" strokeWidth="1" />
                <rect x="17" y="10" width="24" height="32" rx="4" fill="#183527" stroke="#f0dfad" strokeWidth="1.6" />
                <path d="M22 15h5" stroke="#f0dfad" strokeWidth="1.2" />
                <text x="22" y="23" fill="#f0dfad" fontSize="9" fontWeight="700" fontFamily="serif">A</text>
                <path d="M29 23 26.5 26 29 29l2.5-3z" fill="#f0dfad" />
                <path d="M24 31h10M29 27v9" stroke="#d6b75e" strokeWidth="1.8" strokeLinecap="round" />
              </svg>
            </div>
            <h2>Create Game</h2>
            <p>Start a new game with your saved configuration.</p>
            <button className="button lobby-cta" onClick={handleCreate} disabled={loading !== null}>
              <span aria-hidden="true">＋</span>{loading === "creating" ? "Creating…" : "Create Game"}
            </button>
            <div className="lobby-info"><span aria-hidden="true">ⓘ</span> New games use your saved configuration.</div>
          </article>

          <article className="lobby-action-card">
            <div className="lobby-action-icon" aria-hidden="true">
              <svg viewBox="0 0 48 48">
                <rect x="6" y="9" width="23" height="31" rx="4" fill="#244832" stroke="#a9bd97" strokeWidth="1.4" />
                <path d="M11 14h5" stroke="#e7d8a4" strokeWidth="1" />
                <text x="11" y="23" fill="#e7d8a4" fontSize="8" fontWeight="700" fontFamily="serif">Q</text>
                <rect x="19" y="7" width="23" height="32" rx="4" fill="#183527" stroke="#f0dfad" strokeWidth="1.6" />
                <path d="M24 12h5" stroke="#f0dfad" strokeWidth="1" />
                <text x="24" y="21" fill="#f0dfad" fontSize="8" fontWeight="700" fontFamily="serif">K</text>
                <path d="M25 29h10m-4-4 4 4-4 4" fill="none" stroke="#d6b75e" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            </div>
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
              <span className="lobby-trophy" aria-hidden="true">
                <svg viewBox="0 0 40 40">
                  <path d="M12 6h16v9c0 7-3 11-8 11s-8-4-8-11z" fill="none" stroke="currentColor" strokeWidth="2.8" strokeLinejoin="round" />
                  <path d="M12 10H6v3c0 5 3 8 8 8M28 10h6v3c0 5-3 8-8 8M20 26v6m-7 3h14m-11-3h8" fill="none" stroke="currentColor" strokeWidth="2.8" strokeLinecap="round" strokeLinejoin="round" />
                  <path d="m20 9 1.2 3 3.2.2-2.5 2 .8 3-2.7-1.7-2.7 1.7.8-3-2.5-2 3.2-.2z" fill="#d6b75e" />
                </svg>
              </span>
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
