import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { fetchState, startGame, leaveGame, raiseDecks, addBot, removeBot, ChatMessage, GameStateView, openGameSocket } from "../api/game";
import { ApiError } from "../api/client";
import { useAuth } from "../auth/useAuth";
import ErrorAlert from "../components/ErrorAlert";
import ChatPanel from "../components/ChatPanel";
import { appendChatMessage, sendChatMessage } from "../lib/sessionChat";
import { disconnectVoice } from "../lib/voice";
import "../styles/room-header.css";
import "../styles/raise-decks.css";
import "../styles/select-fix.css";
import "../styles/room-bots.css";

/** decksCount is not in GameStateView yet, so it stays optional here until the state response carries it. */
type RoomGameState = GameStateView & { decksCount?: number };

/** Seats of a one-deck game with the default layout (3 face-down + 3 face-up + 3 hand = 9 cards): 52 / 9 = 5. Hardcoded because the state does not send the layout. */
const ONE_DECK_SEATS = 5;
/** Most seats any game has (GameSession.MAX_PLAYERS). */
const MAX_SEATS = 10;

/** Bot types the owner can add. Add an entry (e.g. INTERMEDIATE / "Intermediate bot") to offer another type. */
const BOT_TYPES = [
  { value: "BEGINNER", label: "Beginner bot" }
] as const;

export default function Room() {
  const { sessionId } = useParams();
  const navigate = useNavigate();
  const { token } = useAuth();
  const [state, setState] = useState<RoomGameState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [raiseError, setRaiseError] = useState<string | null>(null);
  const [raisingDecks, setRaisingDecks] = useState(false);
  const raiseRequestInProgress = useRef(false);
  const [botError, setBotError] = useState<string | null>(null);
  const [botType, setBotType] = useState<string>(BOT_TYPES[0].value);
  const [botBusy, setBotBusy] = useState(false);
  const botRequestInProgress = useRef(false);
  const [loading, setLoading] = useState<string | null>(null);
  const failCount = useRef(0);
  const startRequestInProgress = useRef(false);
  const transitionStarted = useRef(false);
  const transitionTimer = useRef<number | null>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const [chatMessages, setChatMessages] = useState<ChatMessage[]>([]);
  const [socketOpen, setSocketOpen] = useState(false);

  const showStartingScreen = useCallback(() => {
    setLoading("starting");
    if (transitionStarted.current || !sessionId) return;
    transitionStarted.current = true;
    transitionTimer.current = window.setTimeout(() => {
      navigate(`/game/${sessionId}`);
    }, 500);
  }, [navigate, sessionId]);

  useEffect(() => {
    if (!sessionId || !token) return;
    const ws = openGameSocket(sessionId, token);
    wsRef.current = ws;
    ws.onopen = () => setSocketOpen(true);
    ws.onclose = () => setSocketOpen(false);
    ws.onmessage = (event) => {
      try {
        const next = JSON.parse(event.data) as GameStateView;
        if ((next as unknown as { type?: string }).type === "chat") {
          setChatMessages((prev) => appendChatMessage(prev, next as unknown as ChatMessage));
          return;
        }
        // Nudges are only played at the game table; the lobby ignores them.
        if ((next as unknown as { type?: string }).type === "nudge") return;
        if ((next as unknown as { type?: string }).type === "error") return;
        setState(next);
        if (next.started) showStartingScreen();
        else if (next.starting) setLoading("starting");
      } catch { /* REST polling remains the fallback for malformed live messages. */ }
    };
    return () => {
      ws.close();
      if (wsRef.current === ws) wsRef.current = null;
    };
  }, [sessionId, token, showStartingScreen]);

  const announceStarting = async () => {
    const ws = wsRef.current;
    if (!ws) return;
    if (ws.readyState === WebSocket.CONNECTING) {
      await new Promise<void>((resolve) => {
        const timeout = window.setTimeout(resolve, 1200);
        ws.addEventListener("open", () => { window.clearTimeout(timeout); resolve(); }, { once: true });
        ws.addEventListener("error", () => { window.clearTimeout(timeout); resolve(); }, { once: true });
      });
    }
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ action: "setup", sessionId, setupAction: "announce" }));
    }
  };

  useEffect(() => {
    if (!sessionId) return;
    let cancelled = false;

    const refresh = async () => {
      try {
        const data = await fetchState(token, sessionId);
        if (cancelled) return;
        setState(data);
        setError(null);
        failCount.current = 0;
        if (data.started) {
          showStartingScreen();
        } else if (data.starting) {
          setLoading("starting");
        }
      } catch (cause) {
        if (cancelled) return;
        if (cause instanceof ApiError && cause.status === 404) {
          navigate("/lobby", {
            replace: true,
            state: { error: "This game is no longer available. It may have been cleared or already ended." }
          });
          return;
        }
        failCount.current++;
        if (failCount.current >= 3) {
          setError("Failed to load lobby state.");
        }
      }
    };

    refresh();
    const handle = setInterval(refresh, 1000);
    return () => {
      cancelled = true;
      clearInterval(handle);
      if (transitionTimer.current !== null) window.clearTimeout(transitionTimer.current);
    };
  }, [sessionId, token, showStartingScreen, navigate]);

  const canStart = useMemo(() => {
    if (!state) return false;
    return state.isOwner && state.players.length >= 2 && !state.started;
  }, [state]);

  // Only the owner of an unstarted one-deck game that is full for one deck can add the second deck.
  const canRaiseDecks = useMemo(() => {
    if (!state) return false;
    return state.isOwner && !state.started && !state.starting
      && state.decksCount === 1 && state.players.length >= ONE_DECK_SEATS;
  }, [state]);

  const onStart = async () => {
    if (!sessionId) return;
    startRequestInProgress.current = true;
    setLoading("starting");
    try {
      // Announce over the already-open lobby socket first so other players see the
      // transition while the REST start request is still running.
      await announceStarting();
      // Allow the server's WebSocket broadcast to reach every lobby before finalizing the deal.
      await new Promise((resolve) => window.setTimeout(resolve, 450));
      await startGame(token, sessionId, "start");
      startRequestInProgress.current = false;
      showStartingScreen();
    } catch (cause) {
      startRequestInProgress.current = false;
      if (cause instanceof ApiError && cause.status === 404) {
        navigate("/lobby", {
          replace: true,
          state: { error: "This game is no longer available. It may have been cleared or already ended." }
        });
        return;
      }
      setError(cause instanceof Error ? cause.message : "Failed to start game.");
      setLoading(null);
    }
  };

  const onRaiseDecks = async () => {
    if (!sessionId || raiseRequestInProgress.current) return;
    raiseRequestInProgress.current = true;
    setRaisingDecks(true);
    setRaiseError(null);
    try {
      await raiseDecks(token, sessionId);
      // Refetch now so the new decksCount and capacity show at once; a failed refetch is covered by the poll.
      const next = await fetchState(token, sessionId).catch(() => null);
      if (next) setState(next);
    } catch (cause) {
      setRaiseError(cause instanceof ApiError ? cause.message : "Couldn't add a second deck. Please try again.");
    } finally {
      raiseRequestInProgress.current = false;
      setRaisingDecks(false);
    }
  };

  const canManageBots = !!state && state.isOwner && !state.started && !state.starting;
  // Custom card layouts can seat more than ONE_DECK_SEATS with one deck, so only the hard cap disables the button;
  // the server answers 409 with the real limit.
  const lobbyFull = !!state && state.players.length >= MAX_SEATS;

  const runBotRequest = async (request: () => Promise<unknown>, fallback: string) => {
    if (!sessionId || botRequestInProgress.current) return;
    botRequestInProgress.current = true;
    setBotBusy(true);
    setBotError(null);
    try {
      await request();
      // Refetch now so the roster updates at once; a failed refetch is covered by the poll.
      const next = await fetchState(token, sessionId).catch(() => null);
      if (next) setState(next);
    } catch (cause) {
      setBotError(cause instanceof ApiError ? cause.message : fallback);
    } finally {
      botRequestInProgress.current = false;
      setBotBusy(false);
    }
  };

  const onAddBot = () => runBotRequest(() => addBot(token, sessionId!, botType), "Couldn't add a bot. Please try again.");
  const onRemoveBot = (botId: string) => runBotRequest(() => removeBot(token, sessionId!, botId), "Couldn't remove the bot. Please try again.");

  return (
    <div className="page fade-in room-page">
      <ErrorAlert message={error} onDismiss={() => setError(null)} />
      <ErrorAlert message={raiseError} onDismiss={() => setRaiseError(null)} />
      <ErrorAlert message={botError} onDismiss={() => setBotError(null)} />
      <header className="room-bar">
        <div className="room-bar-title">
          <h2 className="title">Room {sessionId}</h2>
          {state && (
            <span className="room-bar-count">{state.players.length} {state.players.length === 1 ? "player" : "players"}</span>
          )}
        </div>
        <div className="room-bar-actions">
          {canStart && (
            <button className="button" onClick={onStart} disabled={loading !== null && startRequestInProgress.current}>
              {loading === "starting" && startRequestInProgress.current
                ? "Starting..."
                : state?.starting ? "Continue Start" : "Start Game"}
            </button>
          )}
          <button className="button secondary" disabled={loading !== null} onClick={async () => {
            setLoading("leaving");
            void disconnectVoice(null);
            if (sessionId) {
              try { await leaveGame(token, sessionId); } catch {}
            }
            navigate("/lobby");
          }}>
            {loading === "leaving" ? "Leaving..." : "Leave"}
          </button>
        </div>
      </header>

      <div className="room-columns">
        <div className="glass card room-players">
          {!state && !error && <p style={{ color: "var(--ink-dim)" }}>Loading...</p>}
          <ul className="player-list" aria-label="Players">
            {state?.players.map((player) => (
              <li key={player.playerId} className="player-item">
                <span className="player-name">
                  {player.username}
                  {player.isBot && <span className="badge badge-bot">Bot</span>}
                </span>
                {player.isYou && <span className="badge">You</span>}
                {player.isBot && canManageBots && (
                  <button
                    className="button secondary room-bot-remove"
                    type="button"
                    aria-label={`Remove ${player.username}`}
                    onClick={() => onRemoveBot(player.playerId)}
                    disabled={botBusy}
                  >
                    Remove
                  </button>
                )}
              </li>
            ))}
          </ul>
          {canManageBots && (
            <div className="room-bots">
              <select
                className="input rules-select room-bots-select"
                aria-label="Bot type"
                value={botType}
                onChange={(event) => setBotType(event.target.value)}
                disabled={botBusy}
              >
                {BOT_TYPES.map((type) => (
                  <option key={type.value} value={type.value}>{type.label}</option>
                ))}
              </select>
              <button className="button secondary room-bots-add" type="button" onClick={onAddBot} disabled={botBusy || lobbyFull}>
                {botBusy ? "Working..." : "Add bot"}
              </button>
            </div>
          )}
          {canRaiseDecks && (
            <div className="room-raise-decks">
              <button className="button secondary" type="button" onClick={onRaiseDecks} disabled={raisingDecks}>
                {raisingDecks ? "Adding..." : "Add a second deck"}
              </button>
              <p className="room-raise-decks-hint">This lets up to 10 players join.</p>
            </div>
          )}
        </div>
        <ChatPanel
          messages={chatMessages}
          currentUserId={state?.players.find((player) => player.isYou)?.playerId}
          connected={socketOpen}
          onSend={(text) => (sessionId ? sendChatMessage(wsRef.current, sessionId, text) : false)}
          voiceEnabled={state?.voiceEnabled === true}
          sessionId={sessionId}
          players={state?.players}
          finished={state?.finished}
        />
      </div>
      {loading === "starting" && (
        <div className="game-starting-overlay" role="status" aria-live="polite">
          <div className="game-starting-message">
            <div className="game-starting-spinner" aria-hidden="true" />
            <div>
              <h2 className="title">Starting game…</h2>
              <p style={{ color: "var(--ink-dim)", marginBottom: 0 }}>The owner started the game. Getting everyone to the table.</p>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
