import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useAuth } from "../auth/useAuth";
import { fetchGlobalLeaderboard, fetchSessionLeaderboard, LeaderboardEntry } from "../api/leaderboard";
import Tabs from "../components/Tabs";
import ErrorAlert from "../components/ErrorAlert";
import "../styles/leaderboard.css";

function TrophyIcon() {
  return (
    <span className="lobby-trophy" aria-hidden="true">
      <svg viewBox="0 0 40 40">
        <path d="M12 6h16v9c0 7-3 11-8 11s-8-4-8-11z" fill="none" stroke="currentColor" strokeWidth="2.8" strokeLinejoin="round" />
        <path d="M12 10H6v3c0 5 3 8 8 8M28 10h6v3c0 5-3 8-8 8M20 26v6m-7 3h14m-11-3h8" fill="none" stroke="currentColor" strokeWidth="2.8" strokeLinecap="round" strokeLinejoin="round" />
        <path d="m20 9 1.2 3 3.2.2-2.5 2 .8 3-2.7-1.7-2.7 1.7.8-3-2.5-2 3.2-.2z" fill="#d6b75e" />
      </svg>
    </span>
  );
}

export default function Leaderboard() {
  const { sessionId } = useParams();
  const navigate = useNavigate();
  const { token } = useAuth();
  const [tab, setTab] = useState(sessionId ? "Session" : "Global");
  const [sessionData, setSessionData] = useState<LeaderboardEntry[]>([]);
  const [globalData, setGlobalData] = useState<LeaderboardEntry[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [sessionLoading, setSessionLoading] = useState(Boolean(sessionId));
  const [globalLoading, setGlobalLoading] = useState(true);

  useEffect(() => {
    if (!sessionId) return;
    let active = true;
    setSessionLoading(true);
    fetchSessionLeaderboard(token, sessionId).then((rows) => { if (active) setSessionData(rows); }).catch((cause: unknown) => {
      if (active) setError(cause instanceof Error ? cause.message : "Couldn't load the session leaderboard.");
    }).finally(() => { if (active) setSessionLoading(false); });
    return () => { active = false; };
  }, [sessionId, token]);

  useEffect(() => {
    let active = true;
    setGlobalLoading(true);
    fetchGlobalLeaderboard(token, 20).then((rows) => { if (active) setGlobalData(rows); }).catch((cause: unknown) => {
      if (active) setError(cause instanceof Error ? cause.message : "Couldn't load the global leaderboard.");
    }).finally(() => { if (active) setGlobalLoading(false); });
    return () => { active = false; };
  }, [token]);

  const rows = tab === "Session" ? sessionData : globalData;
  const loading = tab === "Session" ? sessionLoading : globalLoading;

  return (
    <div className="page fade-in leaderboard-page">
      <main className="leaderboard-main">
        <ErrorAlert message={error} onDismiss={() => setError(null)} />
        <header className="lobby-welcome leaderboard-welcome">
          <div>
            <h1>Full leaderboard</h1>
            <p>See how every player ranks by ELO.</p>
          </div>
          <button className="button secondary lobby-rankings-link" onClick={() => navigate("/lobby")}>
            Go to Home
          </button>
        </header>

        <section className="lobby-rankings" aria-labelledby="leaderboard-title">
          <div className="lobby-rankings-heading">
            <div className="lobby-rankings-title-wrap">
              <TrophyIcon />
              <div>
                <h2 id="leaderboard-title">Rankings</h2>
                <p>{sessionId && tab === "Session" ? "Final standings for this game." : "Top players and their current ELO."}</p>
              </div>
            </div>
          </div>

          {sessionId && <Tabs tabs={["Session", "Global"]} active={tab} onChange={setTab} />}

          {loading ? <div className="lobby-rankings-message" role="status"><span className="game-starting-spinner" />Loading rankings…</div>
            : rows.length === 0 ? <p className="lobby-rankings-message">No rankings are available yet.</p>
              : (
                <div className="lobby-table-wrap">
                  <table className="lobby-rankings-table">
                    <thead><tr><th scope="col">#</th><th scope="col">Name</th><th scope="col">ELO</th></tr></thead>
                    <tbody>
                      {rows.map((entry, index) => (
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
