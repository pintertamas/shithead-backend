import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { useAuth } from "../auth/useAuth";
import { fetchGlobalLeaderboard, fetchSessionLeaderboard, LeaderboardEntry } from "../api/leaderboard";
import Tabs from "../components/Tabs";

export default function Leaderboard() {
  const { sessionId } = useParams();
  const { token } = useAuth();
  const [tab, setTab] = useState("Session");
  const [sessionData, setSessionData] = useState<LeaderboardEntry[]>([]);
  const [globalData, setGlobalData] = useState<LeaderboardEntry[]>([]);

  useEffect(() => {
    if (!sessionId) return;
    fetchSessionLeaderboard(token, sessionId).then(setSessionData).catch(() => null);
  }, [sessionId]);

  useEffect(() => {
    fetchGlobalLeaderboard(token, 20).then(setGlobalData).catch(() => null);
  }, []);

  const rows = tab === "Session" ? sessionData : globalData;

  return (
    <div className="page fade-in">
      <div className="topbar">
        <div>
          <div className="badge">Leaderboard</div>
          <h2 className="title">Elo Rankings</h2>
        </div>
      </div>

      <div className="glass card">
        <Tabs tabs={["Session", "Global"]} active={tab} onChange={setTab} />
        <div className="player-list">
          {rows.map((entry, idx) => (
            <div key={entry.userId} className="player-item">
              <span>
                {idx + 1}. {entry.username}
              </span>
              <strong>{Math.round(entry.eloScore)}</strong>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

