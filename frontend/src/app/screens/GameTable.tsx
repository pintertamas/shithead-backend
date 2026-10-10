import { CSSProperties, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
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
import { EMPTY_SELECTION, SelectionState, affectsOwnCardsOrTurn, nextSelection, reconcileSelection } from "../lib/selection";
import { moveSignature, mustPickUp } from "../lib/rules";
import StarterPicker from "../components/StarterPicker";
import PeekWrap from "../components/PeekWrap";
import { SeatChip, SeatPeek } from "../components/SeatChip";
import { describeEvent } from "../lib/gameFeed";
import { GameSocket } from "../lib/gameSocket";
import "../styles/table-mobile.css";
import "../styles/companion-width.css";
import "../styles/table-bar.css";
import "../styles/swap-phase-phone.css";
import "../styles/swap-phase-desktop.css";

/** Phones (portrait and landscape): neighbours as full panels at the sides, other players as chips. */
const PHONE_QUERY = "(max-width: 700px)";

function usePhoneLayout(): boolean {
  const [phone, setPhone] = useState(() => typeof window !== "undefined" && typeof window.matchMedia === "function"
    && window.matchMedia(PHONE_QUERY).matches);
  useEffect(() => {
    if (typeof window.matchMedia !== "function") return undefined;
    const query = window.matchMedia(PHONE_QUERY);
    const update = () => setPhone(query.matches);
    query.addEventListener("change", update);
    return () => query.removeEventListener("change", update);
  }, []);
  return phone;
}

/** No cards on the table or in hand: the same test the next-player search uses. */
function isOutOfGame(player: PlayerState) {
  return player.handCount === 0 && player.faceDownCount === 0 && player.faceUp.length === 0
    && !(player.isYou && (player.hand?.length ?? 0) > 0);
}

/** The player `step` places away from `index` in the server's order (cyclic), skipping players who are out. */
function neighbourPlayerId(players: PlayerState[], index: number, step: 1 | -1): string | null {
  if (index < 0) return null;
  const count = players.length;
  for (let offset = 1; offset < count; offset++) {
    const candidate = players[(((index + step * offset) % count) + count) % count];
    if (candidate && !isOutOfGame(candidate)) return candidate.playerId;
  }
  return null;
}
export default function GameTable() {
  const { sessionId } = useParams();
  const navigate = useNavigate();
  const { token } = useAuth();
  const [state, setState] = useState<GameStateView | null>(null);
  const [showModal, setShowModal] = useState(false);
  // Selection lives here (not in a child) so it survives child remounts such as the chat sheet or layout switches.
  // applyState reconciles it with each state update; see lib/selection.ts.
  const [selection, setSelection] = useState<SelectionState>(EMPTY_SELECTION);
  const selected = selection.selected;
  const pickupSelected = selection.pickupSelected;
  const setSelected = useCallback((next: CardSelection[]) => setSelection((prev) => ({ ...prev, selected: next })), []);
  const setPickupSelected = useCallback((next: boolean) => setSelection((prev) => ({ ...prev, pickupSelected: next })), []);
  const [error, setError] = useState<string | null>(null);
  // Reconnect status ("Reconnecting...", or the failure notice). Kept apart from error: the state poll clears error.
  const [connectionNotice, setConnectionNotice] = useState<string | null>(null);
  const [pendingAction, setPendingAction] = useState(false);
  const pendingRef = useRef(false);
  const phoneChipsRef = useRef<HTMLDivElement>(null);
  const [chatMessages, setChatMessages] = useState<ChatMessage[]>([]);
  const [socketOpen, setSocketOpen] = useState(false);
  // Latest chat line per player id, shown as a speech bubble above that seat.
  const [latestChatByPlayer, setLatestChatByPlayer] = useState<Record<string, { text: string; ts: number }>>({});
  const [nudgeFrom, showNudge] = useNudgeNotice();
  const phone = usePhoneLayout();
  // Phone only: the opponent whose cards are open in the centred peek dialog.
  const [peekId, setPeekId] = useState<string | null>(null);
  const closePeek = useCallback(() => setPeekId(null), []);
  const socketRef = useRef<GameSocket | null>(null);
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
  // No legal move (see lib/rules.ts): the discard pile is selected automatically. The key changes only when the turn,
  // the pile top or the hand/face-up cards change, so a card the player then selects or a pick-up they then drop is not undone.
  const forcedMoveKey = useMemo(() => {
    if (!state || !you) return null;
    const input = {
      setupComplete: state.setupComplete,
      finished: state.finished,
      yourTurn,
      discardPile: state.discardPile ?? [],
      hand: you.hand ?? [],
      faceUp: you.faceUp,
      deckCount: state.deckCount,
      allowMixedHandAndFaceUpWhenDeckEmpty: state.allowMixedHandAndFaceUpWhenDeckEmpty
    };
    return mustPickUp(input) ? moveSignature({ ...input, currentPlayerId: state.currentPlayerId }) : null;
  }, [state, you, yourTurn]);
  const autoPickupKeyRef = useRef<string | null>(null);
  useEffect(() => {
    if (forcedMoveKey === null) {
      autoPickupKeyRef.current = null;
      return;
    }
    if (autoPickupKeyRef.current === forcedMoveKey) return;
    autoPickupKeyRef.current = forcedMoveKey;
    setSelection({ selected: [], pickupSelected: true });
  }, [forcedMoveKey]);
  // The next player is found in the server's state.players order (cyclic after the current player), skipping players who are out.
  const nextPlayerId = useMemo(() => {
    if (!state || setupStage || state.finished) return null;
    const players = state.players;
    const start = players.findIndex((player) => player.playerId === state.currentPlayerId);
    if (start < 0) return null;
    for (let step = 1; step < players.length; step++) {
      const candidate = players[(start + step) % players.length];
      if (!isOutOfGame(candidate)) return candidate.playerId;
    }
    return null;
  }, [state, setupStage]);
  // Phone layout: the players before and after the viewer in the server's order get full panels at the sides.
  // Everyone else is a chip. With two players the single opponent is both neighbours and takes the left slot.
  const phoneSeats = useMemo(() => {
    if (!state) return null;
    // Setup has no turn order yet and no piles, so every opponent is a chip until play starts.
    if (setupStage) return { leftId: null, rightId: null, chips: others };
    const index = state.players.findIndex((player) => player.isYou);
    const leftId = neighbourPlayerId(state.players, index, -1);
    const after = neighbourPlayerId(state.players, index, 1);
    const rightId = after !== leftId ? after : null;
    const chips = others.filter((player) => player.playerId !== leftId && player.playerId !== rightId);
    return { leftId, rightId, chips };
  }, [state, others, setupStage]);

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

  // Mirrors pendingAction for applyState, which runs from WebSocket and timer callbacks rather than render.
  useLayoutEffect(() => { pendingRef.current = pendingAction; }, [pendingAction]);

  // Every state update (WebSocket push or 4s poll) goes through here. The selection is reconciled against the
  // previous state instead of being cleared, so updates that do not touch this player's cards or turn keep it.
  // A pending play/pickup/swap is cleared once the server acknowledges it with a change to this player's cards or turn.
  const applyState = useCallback((next: GameStateView) => {
    const previous = stateRef.current;
    stateRef.current = next;
    setState(next);
    if (!previous) return;
    const pending = pendingRef.current;
    if (pending && affectsOwnCardsOrTurn(previous, next)) {
      setError(null);
      setPendingAction(false);
    }
    setSelection((current) => reconcileSelection(previous, next, current, pending));
  }, []);

  const toggleCard = useCallback((source: CardSelection["source"], index: number) => {
    setSelection((prev) => nextSelection(prev, { source, index }, { hand: you?.hand, faceUp: you?.faceUp }, setupStage ? "setup" : "play"));
  }, [you, setupStage]);

  const sendWs = useCallback((payload: object) => {
    if (socketRef.current?.send(JSON.stringify(payload))) return true;
    // Not open: a reconnect has been started. Nothing is queued, so the player sends the action again once it is back.
    setConnectionNotice("Reconnecting...");
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
    return sessionId ? sendChatMessage(socketRef.current?.currentSocket() ?? null, sessionId, text) : false;
  }, [sessionId]);

  const sendNudgeToTable = useCallback(() => {
    return sessionId ? sendNudge(socketRef.current?.currentSocket() ?? null, sessionId) : false;
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
    const socket = new GameSocket(() => openGameSocket(sessionId, token), {
      onOpen: (reconnected) => {
        setSocketOpen(true);
        setConnectionNotice(null);
        if (!reconnected) return;
        // Updates sent while the socket was down are lost, so the table is read again over REST.
        fetchState(token, sessionId).then((next) => { applyState(next); setError(null); }).catch((cause: unknown) => {
          if (redirectIfGameMissing(cause)) return;
          setError(cause instanceof Error ? cause.message : "Couldn't refresh the game state.");
        });
      },
      onClose: () => {
        setSocketOpen(false);
        // A play or pickup sent on the dropped socket never gets its answer, so the buttons must not stay disabled.
        setPendingAction(false);
      },
      onMessage: (evt) => {
        try {
          const data = JSON.parse(evt.data) as GameStateView;
          if ((data as unknown as { type?: string }).type === "chat") {
            const message = data as unknown as ChatMessage;
            setChatMessages((prev) => appendChatMessage(prev, message));
            setLatestChatByPlayer((prev) => ({ ...prev, [message.userId]: { text: message.text, ts: Date.now() } }));
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
      },
      onUnreachable: () => setConnectionNotice("Still can't reach the game server. We'll keep trying to reconnect.")
    });
    try {
      socket.start();
    } catch {
      setError("The live game URL is invalid. Check the WebSocket endpoint configuration.");
      return;
    }
    socketRef.current = socket;

    return () => {
      socket.dispose();
      if (socketRef.current === socket) socketRef.current = null;
    };
  }, [sessionId, token, applyState, navigate, showNudge, redirectIfGameMissing]);

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

  // Phone: the chip strip scrolls sideways when there are more chips than fit; keep the player whose turn it is in view.
  useEffect(() => {
    const strip = phoneChipsRef.current;
    const turnId = state?.currentPlayerId;
    if (!strip || !turnId) return;
    const chip = Array.from(strip.querySelectorAll<HTMLElement>("[data-seat-id]"))
      .find((element) => element.dataset.seatId === turnId);
    if (!chip) return;
    const offset = chip.getBoundingClientRect().left - strip.getBoundingClientRect().left;
    const target = strip.scrollLeft + offset - (strip.clientWidth - chip.offsetWidth) / 2;
    strip.scrollTo({ left: Math.max(0, target), behavior: "smooth" });
  }, [state?.currentPlayerId, phone, phoneSeats?.chips.length]);

  if (!state || !you) {
    return (
      <div className="page">
        <div className="glass card">Loading game state...</div>
      </div>
    );
  }

  // Swap and Ready. On phones the swap button carries the pair counter (Swap (2↔2)) and the hint is hidden (CSS).
  const swapLabel = phone
    ? `Swap (${selectedStartingHand.length}↔${selectedStartingUp.length})`
    : "Swap selected cards";
  const setupButtons = (
    <>
      <button className="button secondary" disabled={pendingAction || !canSwapStartingCards}
        title={`${selectedStartingHand.length} from hand and ${selectedStartingUp.length} face-up selected. Select the same number on each side to swap.`}
        onClick={() => sendSetup("swap")}>{swapLabel}</button>
      <p className="game-hint">
        {canSwapStartingCards
          ? `${selectedStartingHand.length} ${selectedStartingHand.length === 1 ? "card" : "cards"} selected on each side.`
          : `${selectedStartingHand.length} from hand and ${selectedStartingUp.length} face-up selected. Select the same number on each side to swap.`}
      </p>
      <button className="button" disabled={pendingAction} onClick={() => { setSelected([]); sendSetup("ready"); }}>
        {pendingAction ? "Saving…" : "Ready"}
      </button>
    </>
  );
  const sidePanel = (playerId: string | null | undefined, side: "left" | "right") => {
    const player = playerId ? state.players.find((candidate) => candidate.playerId === playerId) : undefined;
    return (
      <div className={`phone-side phone-side-${side}`}>
        {player && (
          <PlayerPanel player={player} compact isCurrentTurn={state.currentPlayerId === player.playerId}
            isNext={nextPlayerId === player.playerId} chatBubble={latestChatByPlayer[player.playerId]} />
        )}
      </div>
    );
  };
  const peekPlayer = peekId ? state.players.find((candidate) => candidate.playerId === peekId) : undefined;
  const latestEvent = state.events && state.events.length > 0 ? state.events[state.events.length - 1] : null;

  return (
    <div className={`page fade-in game-page${phone ? " phone-table" : ""}${setupStage ? " setup-phase" : ""}`}>
      <ErrorAlert message={error ?? connectionNotice} onDismiss={() => { setError(null); setConnectionNotice(null); }} />
      <NudgeBanner username={nudgeFrom} />

      <div className="game-stage">
      <div className="game-main">
      <header className="table-bar">
        <span className="badge">SHITHEAD</span>
        <h2 className="title table-bar-title">
          <span className="table-bar-code">{state.sessionId}</span>
          <span className="table-bar-sep" aria-hidden="true">·</span>
          <span className="header-player-name table-bar-name">{you.username}</span>
          {Number.isFinite(you.eloScore) && (
            <>
              <span className="table-bar-sep" aria-hidden="true">·</span>
              <span className="table-bar-elo" title="Your Elo">{Math.round(you.eloScore)}</span>
            </>
          )}
        </h2>
        {phone && phoneSeats && phoneSeats.chips.length > 0 && (
          <div className="phone-chips" ref={phoneChipsRef} role="group" aria-label="Other players">
            {phoneSeats.chips.map((player) => (
              <SeatChip key={player.playerId} player={player} isCurrentTurn={state.currentPlayerId === player.playerId}
                isNext={nextPlayerId === player.playerId} chatBubble={latestChatByPlayer[player.playerId]} onOpen={setPeekId} />
            ))}
          </div>
        )}
        <NudgeButton onNudge={sendNudgeToTable} />
      </header>
      <main className="game-board" ref={boardRef} style={{ "--opponent-count": Math.max(1, others.length) } as CSSProperties}>
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
        {!phone && (
          <div className="game-opponents" aria-label="Other players">
            {others.map((player) => (
              <PlayerPanel key={player.playerId} player={player} isCurrentTurn={state.currentPlayerId === player.playerId}
                isNext={nextPlayerId === player.playerId} chatBubble={latestChatByPlayer[player.playerId]} />
            ))}
          </div>
        )}

        <section className={`game-middle${phone ? " phone-middle" : ""}${phone && setupStage ? " phone-setup" : ""}`} aria-label="Game table">
          {phone && sidePanel(phoneSeats?.leftId, "left")}
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
                <StarterPicker players={state.players} currentPlayerId={state.currentPlayerId} isOwner={state.isOwner}
                  disabled={pendingAction} onPick={(starterId) => sendWs({ action: "setup", sessionId, setupAction: "starter", starterId })} />
                {phone ? (
                  <details className="setup-how">
                    <summary>How swapping works</summary>
                    <p>Select cards from your hand and the same number of face-up cards to swap them in pairs. You can change your choice until you’re ready.</p>
                  </details>
                ) : (
                  <p>Select cards from your hand and the same number of face-up cards to swap them in pairs. You can change your choice until you’re ready.</p>
                )}
                {!you.ready ? (phone ? <div className="setup-actions">{setupButtons}</div> : setupButtons)
                  : <p className="ready-confirmation">You’re ready. Waiting for the other players.</p>}
                {phone ? (notReady.length > 0 && (
                  <p className="setup-waiting" title={`Not ready: ${notReady.map((player) => player.username).join(", ")}`}>
                    Waiting for {notReady.length} {notReady.length === 1 ? "player" : "players"}
                  </p>
                )) : (
                  <p className="setup-waiting"><strong>Not everybody is ready</strong>{notReady.length > 0 && <>: {notReady.map((player) => player.username).join(", ")}</>}</p>
                )}
              </div>
            ) : null}
          </div>
          {phone && sidePanel(phoneSeats?.rightId, "right")}
        </section>

        <PlayerPanel
          player={you}
          isCurrentTurn={yourTurn}
          isNext={nextPlayerId === you.playerId}
          chatBubble={latestChatByPlayer[you.playerId]}
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
            {(!yourTurn || pickupSelected || selected.length === 0) && (
              <p className="game-hint">
                {!yourTurn
                  ? `Waiting for ${currentName || "the current player"}'s turn.`
                  : pickupSelected
                    ? "Discard pile selected. Press Pick Up to collect it."
                    : "Select cards, then press Play."}
              </p>
            )}
          </div>
        )}
        {phone && (
          <PeekWrap className="phone-log-wrap" label="Game log" toggleText="Log" pressToOpen
            popover={<div className="phone-log-popover"><GameFeed events={state.events} /></div>}>
            <div className="phone-log-line" role="status">
              {latestEvent ? describeEvent(latestEvent) : "Moves and events will show up here."}
            </div>
          </PeekWrap>
        )}
        <div className="table-fx" ref={fxLayerRef} aria-hidden="true" />
      </main>
      </div>

      <div className="game-companion">
        {!phone && <GameFeed events={state.events} />}
        <ChatPanel messages={chatMessages} currentUserId={you.playerId} connected={socketOpen} onSend={sendChat}
          voiceEnabled={state.voiceEnabled === true} sessionId={state.sessionId} players={state.players} finished={state.finished} />
      </div>
      </div>

      {phone && peekPlayer && (
        <SeatPeek player={peekPlayer} isCurrentTurn={state.currentPlayerId === peekPlayer.playerId} onClose={closePeek} />
      )}

      {showModal && state.shitheadId && (
        <ShitheadModal
          name={state.players.find((p) => p.playerId === state.shitheadId)?.username || "Unknown"}
          onClose={() => navigate(`/leaderboard/${state.sessionId}`)}
        />
      )}
    </div>
  );
}
