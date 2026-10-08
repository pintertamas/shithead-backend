import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { useAuth } from "../auth/useAuth";
import { fetchGlobalLeaderboard, fetchSessionLeaderboard, LeaderboardEntry } from "../api/leaderboard";
import Tabs from "../components/Tabs";
import ErrorAlert from "../components/ErrorAlert";

export default function Leaderboard() {
  const { sessionId } = useParams();
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
    fetchGlobalLeaderboard(token, 20).then((rows) => { if (active) setGlobalData(rows); }).catch((cause: unknown) => {
      if (active) setError(cause instanceof Error ? cause.message : "Couldn't load the global leaderboard.");
    }).finally(() => { if (active) setGlobalLoading(false); });
    return () => { active = false; };
  }, [token]);

  const rows = tab === "Session" ? sessionData : globalData;

  return (
    <div className="page fade-in">
      <ErrorAlert message={error} onDismiss={() => setError(null)} />
      <div className="topbar">
        <div>
          <div className="badge">Leaderboard</div>
          <h2 className="title">Elo Rankings</h2>
        </div>
      </div>

      <div className="glass card">
        {sessionId && <Tabs tabs={["Session", "Global"]} active={tab} onChange={setTab} />}
        <div className="player-list">
          {(tab === "Session" ? sessionLoading : globalLoading) ? <div className="leaderboard-loading" role="status"><span className="game-starting-spinner" />Loading rankings…</div>
            : rows.length === 0 ? <p className="config-note">No rankings are available yet.</p>
              : rows.map((entry, idx) => <div key={entry.userId} className="player-item"><span>{idx + 1}. {entry.username}</span><strong>{Math.round(entry.eloScore)}</strong></div>)}
        </div>
      </div>
    </div>
  );
}

