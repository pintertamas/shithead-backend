import { useEffect, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { fetchState, GameStateView } from "../api/game";
import { useAuth } from "../auth/useAuth";
import Hand from "../components/Hand";
import FaceUp from "../components/FaceUp";
import FaceDownCount from "../components/FaceDownCount";
import Pile from "../components/Pile";
import PlayerPanel from "../components/PlayerPanel";
import TurnBadge from "../components/TurnBadge";
import ShitheadModal from "../components/ShitheadModal";

const WS_BASE = import.meta.env.VITE_WS_BASE_URL;

export default function GameTable() {
  const { sessionId } = useParams();
  const navigate = useNavigate();
  const { token } = useAuth();
  const [state, setState] = useState<GameStateView | null>(null);
  const [showModal, setShowModal] = useState(false);

  useEffect(() => {
    if (!sessionId) return;
    fetchState(token, sessionId).then(setState).catch(() => null);
  }, [sessionId, token]);

  useEffect(() => {
    if (!sessionId) return;
    const handle = setInterval(() => {
      fetchState(token, sessionId).then(setState).catch(() => null);
    }, 4000);
    return () => clearInterval(handle);
  }, [sessionId, token]);

  useEffect(() => {
    if (!sessionId) return;
    const url = `${WS_BASE}?game_session_id=${sessionId}`;
    const ws = new WebSocket(url, [`Bearer ${token}`]);

    ws.onmessage = (evt) => {
      try {
        const data = JSON.parse(evt.data) as GameStateView;
        setState(data);
      } catch {
        return;
      }
    };

    ws.onerror = () => {
      return;
    };

    return () => {
      ws.close();
    };
  }, [sessionId, token]);

  useEffect(() => {
    if (state?.finished && state.shitheadId) {
      setShowModal(true);
    }
  }, [state?.finished, state?.shitheadId]);

  const you = useMemo(() => state?.players.find((p) => p.isYou), [state]);
  const others = useMemo(() => state?.players.filter((p) => !p.isYou) || [], [state]);
  const currentName = useMemo(() => {
    if (!state?.currentPlayerId) return "";
    return state.players.find((p) => p.playerId === state.currentPlayerId)?.username || "";
  }, [state]);

  if (!state || !you) {
    return (
      <div className="page">
        <div className="glass card">Loading game state…</div>
      </div>
    );
  }

  return (
    <div className="page fade-in">
      <div className="topbar">
        <div>
          <div className="badge">Game</div>
          <h2 className="title">Session {state.sessionId}</h2>
        </div>
        {currentName && <TurnBadge name={currentName} />}
      </div>

      <div className="layout">
        <div className="glass card">
          <h3 className="title">Opponents</h3>
          <div className="player-list">
            {others.map((player) => (
              <PlayerPanel key={player.playerId} player={player} />
            ))}
          </div>
        </div>

        <div className="glass card">
          <h3 className="title">Table</h3>
          <div className="grid" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))" }}>
            <Pile title="Draw Pile" count={state.deckCount} />
            <Pile title="Discard" count={state.discardCount} cards={state.discardPile} />
            <FaceDownCount count={you.faceDownCount} />
          </div>
          <div style={{ height: 20 }} />
          <h4 className="title">Your Face Up</h4>
          <FaceUp cards={you.faceUp} />
        </div>

        <div className="glass card">
          <h3 className="title">Your Hand</h3>
          <Hand cards={you.hand || []} />
        </div>
      </div>

      {showModal && state.shitheadId && (
        <ShitheadModal
          name={state.players.find((p) => p.playerId === state.shitheadId)?.username || "Unknown"}
          onClose={() => navigate(`/leaderboard/${state.sessionId}`)}
        />
      )}
    </div>
  );
}

