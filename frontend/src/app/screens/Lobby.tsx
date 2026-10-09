import { useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { createGame, joinGame } from "../api/game";
import { useAuth } from "../auth/useAuth";
import ErrorAlert from "../components/ErrorAlert";
import { fetchProfile } from "../api/profile";
import { getCreateGameConfig, loadGameConfig } from "../config/gameConfig";
import { fetchGlobalLeaderboard, LeaderboardEntry } from "../api/leaderboard";
import { consumeLoginSound, playFart, unlockAudio } from "../lib/fartSound";

export default function Lobby() {
  const navigate = useNavigate();
  const location = useLocation();
  const { token } = useAuth();
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
    // Right after a login the browser may block autoplay, so fall back to the first click or key press.
    if (!consumeLoginSound()) return;
    void playFart().then((played) => { if (!played) unlockAudio(); });
  }, []);

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
    <div className="lobby-content fade-in">
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
              Full leaderboard
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
    </div>
  );
}
