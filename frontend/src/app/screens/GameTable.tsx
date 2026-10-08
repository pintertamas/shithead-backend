import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { CardSelection, fetchState, GameStateView } from "../api/game";
import { ApiError } from "../api/client";
import { useAuth } from "../auth/useAuth";
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

  const redirectIfGameMissing = useCallback((cause: unknown) => {
    if (cause instanceof ApiError && cause.status === 404) {
      navigate("/lobby", {
        replace: true,
        state: { error: "This game is no longer available. It may have been cleared or already ended." }
      });
      return true;
    }
    return false;
  }, [navigate]);

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
      if (redirectIfGameMissing(cause)) return;
      setError(cause instanceof Error ? cause.message : "Couldn't load the game state.");
    });
  }, [sessionId, token, applyState, redirectIfGameMissing]);

  useEffect(() => {
    if (!sessionId) return;
    const handle = setInterval(() => {
      fetchState(token, sessionId).then(applyState).catch((cause: unknown) => {
        if (redirectIfGameMissing(cause)) return;
        setError(cause instanceof Error ? cause.message : "Couldn't refresh the game state.");
      });
    }, 4000);
    return () => clearInterval(handle);
  }, [sessionId, token, applyState, redirectIfGameMissing]);

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
          const errorData = data as unknown as { message?: string; status?: number };
          if (errorData.status === 404) {
            navigate("/lobby", {
              replace: true,
              state: { error: "This game is no longer available. It may have been cleared or already ended." }
            });
            return;
          }
          const message = errorData.message;
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
  }, [sessionId, token, applyState, navigate]);

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
    <div className="page fade-in game-page">
      <ErrorAlert message={error} onDismiss={() => setError(null)} />
      <div className="topbar">
        <div>
          <div className="badge">Game</div>
          <h2 className="title">Session {state.sessionId}</h2>
          <div className="game-player-name">You are playing as <strong>{you.username}</strong></div>
        </div>
        {currentName && <TurnBadge name={currentName} />}
      </div>

      <main className="game-board">
        <div className="game-opponents" aria-label="Other players">
          {others.map((player) => (
            <PlayerPanel key={player.playerId} player={player} isCurrentTurn={state.currentPlayerId === player.playerId} />
          ))}
        </div>

        <section className="game-middle" aria-label="Game table">
          <div className="game-piles">
            <Pile title="Draw pile" count={state.deckCount} />
            <Pile title="Discard pile" count={state.discardCount} cards={state.discardPile} />
          </div>
          <div className="game-actions">
            <button className="button" disabled={selected.length === 0 || mixedSelectionIncomplete || pendingAction || !yourTurn} onClick={playSelected}>
              {pendingAction ? "Sending..." : `Play${selected.length > 0 ? ` (${selected.length})` : ""}`}
            </button>
            <button className="button secondary" disabled={!yourTurn || !pileHasCards || pendingAction} onClick={pickup}>
              Pick Up Pile
            </button>
            <p className="game-hint">
              {!yourTurn
                ? `Waiting for ${currentName || "the current player"}'s turn.`
                : selected.length === 0
                  ? "Select cards, then press Play."
                  : "Selected cards are highlighted. Press Play to submit your move."}
            </p>
          </div>
        </section>

        <PlayerPanel
          player={you}
          isCurrentTurn={yourTurn}
          canSelectFaceUp={canSelectFaceUp || canMixHandAndFaceUp}
          canSelectFaceDown={hand.length === 0 && you.faceUp.length === 0}
          selectedFaceUp={selected.filter((item) => item.source === "faceUp").map((item) => item.index)}
          selectedFaceDown={selected.filter((item) => item.source === "faceDown").map((item) => item.index)}
          selectedHand={selected.filter((item) => item.source === "hand").map((item) => item.index)}
          onToggleFaceUp={(idx) => toggleCard("faceUp", idx)}
          onToggleFaceDown={(idx) => toggleCard("faceDown", idx)}
          onToggleHand={(idx) => toggleCard("hand", idx)}
        />
      </main>

      {showModal && state.shitheadId && (
        <ShitheadModal
          name={state.players.find((p) => p.playerId === state.shitheadId)?.username || "Unknown"}
          onClose={() => navigate(`/leaderboard/${state.sessionId}`)}
        />
      )}
    </div>
  );
}
