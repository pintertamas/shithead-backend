import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { CardSelection, ChatMessage, fetchState, GameStateView, NudgeMessage, openGameSocket, PlayerState } from "../api/game";
import { appendChatMessage, sendChatMessage } from "../lib/sessionChat";
import { sendNudge } from "../lib/fartSound";
import NudgeButton, { NudgeBanner, useNudgeNotice } from "../components/NudgeButton";
import { ApiError } from "../api/client";
import { useAuth } from "../auth/useAuth";
import { playTableTransitions } from "../lib/tableAnimations";
import { CardFaceContent, isRedSuit } from "../components/CardFace";
import Pile from "../components/Pile";
import PlayerPanel from "../components/PlayerPanel";
import ShitheadModal from "../components/ShitheadModal";
import ErrorAlert from "../components/ErrorAlert";
import GameFeed from "../components/GameFeed";
import ChatPanel from "../components/ChatPanel";

export default function GameTable() {
  const { sessionId } = useParams();
  const navigate = useNavigate();
  const { token } = useAuth();
  const [state, setState] = useState<GameStateView | null>(null);
  const [showModal, setShowModal] = useState(false);
  const [selected, setSelected] = useState<CardSelection[]>([]);
  const [pickupSelected, setPickupSelected] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pendingAction, setPendingAction] = useState(false);
  const [chatMessages, setChatMessages] = useState<ChatMessage[]>([]);
  const [socketOpen, setSocketOpen] = useState(false);
  const [nudgeFrom, showNudge] = useNudgeNotice();
  const wsRef = useRef<WebSocket | null>(null);
  const stateRef = useRef<GameStateView | null>(null);
  const boardRef = useRef<HTMLElement>(null);
  const fxLayerRef = useRef<HTMLDivElement>(null);
  const animatedStateRef = useRef<GameStateView | null>(null);

  const you = useMemo(() => state?.players.find((p) => p.isYou), [state]);
  // Seats keep the order in which player ids were first seen, so a REVERSE (which reverses state.players) does not reshuffle them.
  const seatOrderRef = useRef<string[]>([]);
  const seatOrder = useMemo(() => {
    if (!state) return seatOrderRef.current;
    const known = seatOrderRef.current;
    const fresh = state.players.map((player) => player.playerId).filter((id) => !known.includes(id));
    if (fresh.length > 0) seatOrderRef.current = [...known, ...fresh];
    return seatOrderRef.current;
  }, [state]);
  const others = useMemo(() => {
    const rank = new Map(seatOrder.map((id, index) => [id, index] as const));
    return (state?.players.filter((p) => !p.isYou) || [])
      .slice()
      .sort((a, b) => (rank.get(a.playerId) ?? 0) - (rank.get(b.playerId) ?? 0));
  }, [state, seatOrder]);
  const currentName = useMemo(() => {
    if (!state?.currentPlayerId) return "";
    return state.players.find((p) => p.playerId === state.currentPlayerId)?.username || "";
  }, [state]);
  const yourTurn = Boolean(state && you && state.currentPlayerId === you.playerId);
  const pileHasCards = (state?.discardCount ?? 0) > 0;
  const hand = you?.hand || [];
  const setupStage = Boolean(state && state.started && !state.setupComplete);
  const canSelectFaceUp = Boolean(you && (setupStage ? !you.ready : hand.length === 0));
  const canMixHandAndFaceUp = Boolean(state?.allowMixedHandAndFaceUpWhenDeckEmpty && state.deckCount === 0);
  const selectedHasHand = selected.some((item) => item.source === "hand");
  const selectedHasFaceUp = selected.some((item) => item.source === "faceUp");
  const mixedSelectionIncomplete = canMixHandAndFaceUp && hand.length > 0 && selectedHasFaceUp && !selectedHasHand;
  const selectedStartingHand = selected.filter((item) => item.source === "hand");
  const selectedStartingUp = selected.filter((item) => item.source === "faceUp");
  const canSwapStartingCards = selectedStartingHand.length > 0 && selectedStartingHand.length === selectedStartingUp.length;
  const notReady = state?.players.filter((player) => !player.ready) || [];
  // The next player is found in the server's state.players order (cyclic after the current player), skipping players who are out.
  const nextPlayerId = useMemo(() => {
    if (!state || setupStage || state.finished) return null;
    const players = state.players;
    const start = players.findIndex((player) => player.playerId === state.currentPlayerId);
    if (start < 0) return null;
    const isOut = (player: PlayerState) => player.handCount === 0 && player.faceDownCount === 0 && player.faceUp.length === 0
      && !(player.isYou && (player.hand?.length ?? 0) > 0);
    for (let step = 1; step < players.length; step++) {
      const candidate = players[(start + step) % players.length];
      if (!isOut(candidate)) return candidate.playerId;
    }
    return null;
  }, [state, setupStage]);

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
      value.setupComplete,
      value.players.map((player) => [player.playerId, player.handCount, player.hand, player.faceUp, player.faceDownCount, player.ready, player.eloScore])
    ]);
    if (previous && signature(previous) !== signature(next)) {
      setSelected([]);
      setPickupSelected(false);
      setError(null);
      setPendingAction(false);
    }
    stateRef.current = next;
    setState(next);
  }, []);

  const toggleCard = useCallback((source: CardSelection["source"], index: number) => {
    setPickupSelected(false);
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
    if (pickupSelected) {
      if (!yourTurn || !pileHasCards) return;
      setError(null);
      if (sendWs({ action: "pickup", sessionId })) setPendingAction(true);
      return;
    }
    if (selected.length === 0 || !yourTurn) return;
    setError(null);
    const selections = selected.map(({ source, index }) => ({
      source: source === "faceUp" ? "FACE_UP" : source === "faceDown" ? "FACE_DOWN" : "HAND",
      index
    }));
    if (sendWs({ action: "play", sessionId, selections })) setPendingAction(true);
  }, [sessionId, selected, you, yourTurn, pickupSelected, pileHasCards, sendWs]);

  const sendSetup = useCallback((setupAction: "ready" | "swap") => {
    if (!sessionId || pendingAction) return;
    if (setupAction === "swap" && !canSwapStartingCards) return;
    // The i-th selected hand card is swapped with the i-th selected face-up card. The first pair is also sent as
    // handIndex/faceUpIndex so a backend without multi-card support still performs a single swap.
    const handIndices = selectedStartingHand.map((item) => item.index);
    const faceUpIndices = selectedStartingUp.map((item) => item.index);
    const payload = setupAction === "swap"
      ? { action: "setup", sessionId, setupAction, handIndices, faceUpIndices, handIndex: handIndices[0], faceUpIndex: faceUpIndices[0] }
      : { action: "setup", sessionId, setupAction };
    setError(null);
    if (sendWs(payload)) setPendingAction(true);
  }, [sessionId, pendingAction, canSwapStartingCards, selectedStartingHand, selectedStartingUp, sendWs]);

  const sendChat = useCallback((text: string) => {
    return sessionId ? sendChatMessage(wsRef.current, sessionId, text) : false;
  }, [sessionId]);

  const sendNudgeToTable = useCallback(() => {
    return sessionId ? sendNudge(wsRef.current, sessionId) : false;
  }, [sessionId]);

  useEffect(() => {
    if (!sessionId) return;
    fetchState(token, sessionId).then((next) => { applyState(next); setError(null); }).catch((cause: unknown) => {
      if (redirectIfGameMissing(cause)) return;
      setError(cause instanceof Error ? cause.message : "Couldn't load the game state.");
    });
  }, [sessionId, token, applyState, redirectIfGameMissing]);

  useEffect(() => {
    if (!sessionId) return;
    const handle = setInterval(() => {
      fetchState(token, sessionId).then((next) => { applyState(next); setError(null); }).catch((cause: unknown) => {
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
      ws = openGameSocket(sessionId, token);
    } catch {
      setError("The live game URL is invalid. Check the WebSocket endpoint configuration.");
      return;
    }
    wsRef.current = ws;

    ws.onopen = () => setSocketOpen(true);

    ws.onmessage = (evt) => {
      try {
        const data = JSON.parse(evt.data) as GameStateView;
        if ((data as unknown as { type?: string }).type === "chat") {
          setChatMessages((prev) => appendChatMessage(prev, data as unknown as ChatMessage));
          return;
        }
        if ((data as unknown as { type?: string }).type === "nudge") {
          showNudge((data as unknown as NudgeMessage).username);
          return;
        }
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
      setSocketOpen(false);
      if (wsRef.current === ws && event.code !== 1000) {
        setError("The live game connection closed unexpectedly. Reload the page to reconnect.");
      }
    };

    return () => {
      ws.close();
      wsRef.current = null;
    };
  }, [sessionId, token, applyState, navigate, showNudge]);

  useEffect(() => {
    if (state?.finished && state.shitheadId) {
      setShowModal(true);
    }
  }, [state?.finished, state?.shitheadId]);

  useLayoutEffect(() => {
    const previous = animatedStateRef.current;
    animatedStateRef.current = state;
    if (!state || !previous || previous === state || !fxLayerRef.current) return;
    try {
      playTableTransitions(previous, state, fxLayerRef.current);
    } catch {
      // Animations are decorative; a failure here must never break the table.
    }
  }, [state]);

  useEffect(() => {
    if (!state?.revealedCard) return;
    const timeout = window.setTimeout(() => {
      setState((current) => current ? { ...current, revealedCard: null } : current);
    }, 1100);
    return () => window.clearTimeout(timeout);
  }, [state?.revealedCard]);

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
      <NudgeBanner username={nudgeFrom} />

      <div className="game-stage">
      <div className="game-main">
      <header className="table-bar">
        <span className="badge">SHITHEAD</span>
        <h2 className="title table-bar-title">{state.sessionId} <span className="header-player-name">· {you.username}</span></h2>
        <NudgeButton onNudge={sendNudgeToTable} />
      </header>
      <main className="game-board" ref={boardRef}>
        {state.revealedCard && (
          <div className="failed-blind-reveal" role="status">
            <span className="blind-flip" aria-hidden="true">
              <span className="blind-flip-inner">
                <span className="blind-flip-face blind-flip-back" />
                <span className={`blind-flip-face blind-flip-front playing-card face-up-card${isRedSuit(state.revealedCard.suit) ? " red-card" : ""}`}>
                  <CardFaceContent card={state.revealedCard} />
                </span>
              </span>
            </span>
            Revealed {state.revealedCard.value} of {state.revealedCard.suit.toLowerCase()}.
          </div>
        )}
        <div className="game-opponents" aria-label="Other players">
          {others.map((player) => (
            <PlayerPanel key={player.playerId} player={player} isCurrentTurn={state.currentPlayerId === player.playerId} isNext={nextPlayerId === player.playerId} />
          ))}
        </div>

        <section className="game-middle" aria-label="Game table">
          <div className="game-piles">
            <Pile title="Draw pile" count={state.deckCount} variant="draw" fxAnchor="draw" />
            <Pile
              title="Discard pile"
              count={state.discardCount}
              cards={state.discardPile}
              selectable={!setupStage}
              selected={pickupSelected}
              disabled={!yourTurn || !pileHasCards || pendingAction}
              onClick={() => { setSelected([]); setPickupSelected(true); }}
              fxAnchor="discard"
            />
          </div>
          <div className="game-actions">
            {setupStage ? (
              <div className="setup-controls">
                <h3 className="title">Choose your starting cards</h3>
                <p>Select cards from your hand and the same number of face-up cards to swap them in pairs. You can change your choice until you’re ready.</p>
                {!you.ready ? (
                  <>
                    <button className="button secondary" disabled={pendingAction || !canSwapStartingCards}
                      onClick={() => sendSetup("swap")}>Swap selected cards</button>
                    <p className="game-hint">
                      {canSwapStartingCards
                        ? `${selectedStartingHand.length} ${selectedStartingHand.length === 1 ? "card" : "cards"} selected on each side.`
                        : `${selectedStartingHand.length} from hand and ${selectedStartingUp.length} face-up selected. Select the same number on each side to swap.`}
                    </p>
                    <button className="button" disabled={pendingAction} onClick={() => { setSelected([]); sendSetup("ready"); }}>
                      {pendingAction ? "Saving…" : "Ready"}
                    </button>
                  </>
                ) : <p className="ready-confirmation">You’re ready. Waiting for the other players.</p>}
                <p className="setup-waiting"><strong>Not everybody is ready</strong>{notReady.length > 0 && <>: {notReady.map((player) => player.username).join(", ")}</>}</p>
              </div>
            ) : null}
          </div>
        </section>

        <PlayerPanel
          player={you}
          isCurrentTurn={yourTurn}
          isNext={nextPlayerId === you.playerId}
          canSelectFaceUp={canSelectFaceUp || (canMixHandAndFaceUp && !setupStage)}
          canSelectFaceDown={!setupStage && hand.length === 0 && you.faceUp.length === 0}
          selectedFaceUp={selected.filter((item) => item.source === "faceUp").map((item) => item.index)}
          selectedFaceDown={selected.filter((item) => item.source === "faceDown").map((item) => item.index)}
          selectedHand={selected.filter((item) => item.source === "hand").map((item) => item.index)}
          onToggleFaceUp={(idx) => toggleCard("faceUp", idx)}
          onToggleFaceDown={(idx) => toggleCard("faceDown", idx)}
          onToggleHand={setupStage && you.ready ? undefined : (idx) => toggleCard("hand", idx)}
        />
        {!setupStage && (
          <div className="game-actions game-actions-play">
            <button className="button" disabled={(!pickupSelected && (selected.length === 0 || mixedSelectionIncomplete)) || pendingAction || !yourTurn || (pickupSelected && !pileHasCards)} onClick={playSelected}>
              {pendingAction ? "Sending..." : pickupSelected ? "Pick Up" : `Play${selected.length > 0 ? ` (${selected.length})` : ""}`}
            </button>
            <p className="game-hint">
              {!yourTurn
                ? `Waiting for ${currentName || "the current player"}'s turn.`
                : pickupSelected
                  ? "Discard pile selected. Press Pick Up to collect it."
                  : selected.length === 0
                  ? "Select cards, then press Play."
                  : "Selected cards are highlighted. Press Play to submit your move."}
            </p>
          </div>
        )}
        <div className="table-fx" ref={fxLayerRef} aria-hidden="true" />
      </main>
      </div>

      <div className="game-companion">
        <GameFeed events={state.events} />
        <ChatPanel messages={chatMessages} currentUserId={you.playerId} connected={socketOpen} onSend={sendChat} />
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
