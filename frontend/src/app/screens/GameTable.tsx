import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { CardSelection, fetchState, GameStateView } from "../api/game";
import { useAuth } from "../auth/useAuth";
import Hand from "../components/Hand";
import FaceUp from "../components/FaceUp";
import FaceDownCount from "../components/FaceDownCount";
import Pile from "../components/Pile";
import PlayerPanel from "../components/PlayerPanel";
import TurnBadge from "../components/TurnBadge";
import ShitheadModal from "../components/ShitheadModal";
import ErrorAlert from "../components/ErrorAlert";

const WS_BASE = import.meta.env.VITE_WS_BASE_URL;

export default function GameTable() {
  const { sessionId } = useParams();
  const navigate = useNavigate();
  const { token } = useAuth();
  const [state, setState] = useState<GameStateView | null>(null);
  const [showModal, setShowModal] = useState(false);
  const [selected, setSelected] = useState<CardSelection[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [pendingAction, setPendingAction] = useState(false);
  const wsRef = useRef<WebSocket | null>(null);
  const stateRef = useRef<GameStateView | null>(null);

  const you = useMemo(() => state?.players.find((p) => p.isYou), [state]);
  const others = useMemo(() => state?.players.filter((p) => !p.isYou) || [], [state]);
  const currentName = useMemo(() => {
    if (!state?.currentPlayerId) return "";
    return state.players.find((p) => p.playerId === state.currentPlayerId)?.username || "";
  }, [state]);
  const yourTurn = Boolean(state && you && state.currentPlayerId === you.playerId);
  const pileHasCards = (state?.discardCount ?? 0) > 0;
  const hand = you?.hand || [];
  const canSelectFaceUp = Boolean(you && hand.length === 0);
  const canMixHandAndFaceUp = Boolean(state?.allowMixedHandAndFaceUpWhenDeckEmpty && state.deckCount === 0);
  const selectedHasHand = selected.some((item) => item.source === "hand");
  const selectedHasFaceUp = selected.some((item) => item.source === "faceUp");
  const mixedSelectionIncomplete = canMixHandAndFaceUp && hand.length > 0 && selectedHasFaceUp && !selectedHasHand;

  const applyState = useCallback((next: GameStateView) => {
    const previous = stateRef.current;
    const signature = (value: GameStateView) => JSON.stringify([
      value.currentPlayerId,
      value.discardCount,
      value.deckCount,
      value.players.map((player) => [player.playerId, player.handCount, player.faceUp.length, player.faceDownCount])
    ]);
    if (previous && signature(previous) !== signature(next)) {
      setSelected([]);
      setError(null);
      setPendingAction(false);
    }
    stateRef.current = next;
    setState(next);
  }, []);

  const toggleCard = useCallback((source: CardSelection["source"], index: number) => {
    setSelected((prev) => {
      const exists = prev.some((item) => item.source === source && item.index === index);
      if (exists) return prev.filter((item) => item.source !== source || item.index !== index);
      if (source === "faceDown" || prev.some((item) => item.source === "faceDown")) return [{ source, index }];
      return [...prev, { source, index }];
    });
  }, []);

  const sendWs = useCallback((payload: object) => {
    const ws = wsRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(payload));
      return true;
    }
    setError(ws?.readyState === WebSocket.CONNECTING
      ? "The game connection is still opening. Please try again in a moment."
      : "The live game connection is closed. Refresh the page to reconnect.");
    return false;
  }, []);

  const playSelected = useCallback(() => {
    if (!sessionId || !you) return;
    if (selected.length === 0 || !yourTurn) return;
    setError(null);
    const selections = selected.map(({ source, index }) => ({
      source: source === "faceUp" ? "FACE_UP" : source === "faceDown" ? "FACE_DOWN" : "HAND",
      index
    }));
    if (sendWs({ action: "play", sessionId, selections })) setPendingAction(true);
  }, [sessionId, selected, you, yourTurn, sendWs]);

  const pickup = useCallback(() => {
    if (!sessionId || !yourTurn || !pileHasCards) return;
    setError(null);
    if (sendWs({ action: "pickup", sessionId })) setPendingAction(true);
  }, [sessionId, yourTurn, pileHasCards, sendWs]);

  useEffect(() => {
    if (!sessionId) return;
    fetchState(token, sessionId).then(applyState).catch((cause: unknown) => {
      setError(cause instanceof Error ? cause.message : "Couldn't load the game state.");
    });
  }, [sessionId, token, applyState]);

  useEffect(() => {
    if (!sessionId) return;
    const handle = setInterval(() => {
      fetchState(token, sessionId).then(applyState).catch((cause: unknown) => {
        setError(cause instanceof Error ? cause.message : "Couldn't refresh the game state.");
      });
    }, 4000);
    return () => clearInterval(handle);
  }, [sessionId, token, applyState]);

  useEffect(() => {
    if (!sessionId || !token) return;
    let ws: WebSocket;
    try {
      const url = new URL(WS_BASE);
      if (url.pathname === "/" || url.pathname === "") {
        url.pathname = "/$default";
      }
      url.searchParams.set("game_session_id", sessionId);
      url.searchParams.set("token", token);
      ws = new WebSocket(url.toString());
    } catch {
      setError("The live game URL is invalid. Check the WebSocket endpoint configuration.");
      return;
    }
    wsRef.current = ws;

    ws.onmessage = (evt) => {
      try {
        const data = JSON.parse(evt.data) as GameStateView;
        if ((data as unknown as { type?: string }).type === "error") {
          const message = (data as unknown as { message?: string }).message;
          setError(message || "The game rejected that action.");
          setPendingAction(false);
          return;
        }
        applyState(data);
      } catch {
        setError("Received an unreadable update from the game server.");
        return;
      }
    };

    ws.onerror = () => {
      setError("The live game connection failed. Reload the page to reconnect.");
    };

    ws.onclose = (event) => {
      if (wsRef.current === ws && event.code !== 1000) {
        setError("The live game connection closed unexpectedly. Reload the page to reconnect.");
      }
    };

    return () => {
      ws.close();
      wsRef.current = null;
    };
  }, [sessionId, token, applyState]);

  useEffect(() => {
    if (state?.finished && state.shitheadId) {
      setShowModal(true);
    }
  }, [state?.finished, state?.shitheadId]);

  if (!state || !you) {
    return (
      <div className="page">
        <div className="glass card">Loading game state...</div>
      </div>
    );
  }

  return (
    <div className="page fade-in">
      <ErrorAlert message={error} onDismiss={() => setError(null)} />
      <div className="topbar">
        <div>
          <div className="badge">Game</div>
          <h2 className="title">Session {state.sessionId}</h2>
          <div className="game-player-name">You are playing as <strong>{you.username}</strong></div>
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
            <FaceDownCount
              count={you.faceDownCount}
              selectable={hand.length === 0 && you.faceUp.length === 0}
              selected={selected.filter((item) => item.source === "faceDown").map((item) => item.index)}
              onToggle={(idx) => toggleCard("faceDown", idx)}
            />
          </div>
          <div style={{ height: 20 }} />
          <h4 className="title">Your Face Up</h4>
          <FaceUp
            cards={you.faceUp}
            selected={selected.filter((item) => item.source === "faceUp").map((item) => item.index)}
            onToggle={canSelectFaceUp || canMixHandAndFaceUp ? (idx) => toggleCard("faceUp", idx) : undefined}
          />
        </div>

        <div className="glass card">
          <h3 className="title">Your Hand</h3>
          <Hand
            cards={hand}
            selected={selected.filter((item) => item.source === "hand").map((item) => item.index)}
            onToggle={(idx) => toggleCard("hand", idx)}
          />
          <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
            <button className="button" disabled={selected.length === 0 || mixedSelectionIncomplete || pendingAction || !yourTurn} onClick={playSelected}>
              {pendingAction ? "Sending..." : `Play${selected.length > 0 ? ` (${selected.length})` : ""}`}
            </button>
            <button className="button secondary" disabled={!yourTurn || !pileHasCards || pendingAction} onClick={pickup}>
              Pick Up Pile
            </button>
          </div>
          <p className="game-hint">
            {!yourTurn
              ? `Waiting for ${currentName || "the current player"}'s turn.`
              : selected.length === 0
                ? "Select one or more cards from your hand, then press Play."
                : "Selected cards are highlighted. Press Play to submit your move."}
          </p>
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
