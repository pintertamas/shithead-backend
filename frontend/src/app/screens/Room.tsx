import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { fetchState, startGame, leaveGame, ChatMessage, GameStateView, openGameSocket } from "../api/game";
import { ApiError } from "../api/client";
import { useAuth } from "../auth/useAuth";
import ErrorAlert from "../components/ErrorAlert";
import ChatPanel from "../components/ChatPanel";
import VoicePanel from "../components/VoicePanel";
import { appendChatMessage, sendChatMessage } from "../lib/sessionChat";

export default function Room() {
  const { sessionId } = useParams();
  const navigate = useNavigate();
  const { token } = useAuth();
  const [state, setState] = useState<GameStateView | null>(null);
  const [error, setError] = useState<string | null>(null);
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

  return (
    <div className="page fade-in">
      <ErrorAlert message={error} onDismiss={() => setError(null)} />
      <div className="topbar">
        <div>
          <div className="badge">Lobby</div>
          <h2 className="title">Room {sessionId}</h2>
        </div>
        <div style={{ display: "flex", gap: 8 }}>
          {state?.voiceEnabled === true && sessionId && <VoicePanel sessionId={sessionId} players={state.players} finished={state.finished} />}
          {canStart && (
            <button className="button" onClick={onStart} disabled={loading !== null && startRequestInProgress.current}>
              {loading === "starting" && startRequestInProgress.current
                ? "Starting..."
                : state?.starting ? "Continue Start" : "Start Game"}
            </button>
          )}
          <button className="button secondary" disabled={loading !== null} onClick={async () => {
            setLoading("leaving");
            if (sessionId) {
              try { await leaveGame(token, sessionId); } catch {}
            }
            navigate("/lobby");
          }}>
            {loading === "leaving" ? "Leaving..." : "Leave"}
          </button>
        </div>
      </div>

      <div className="room-columns">
        <div className="glass card room-players">
          <h3 className="title">Players</h3>
          {!state && !error && <p style={{ color: "var(--ink-dim)" }}>Loading...</p>}
          <div className="player-list">
            {state?.players.map((player) => (
              <div key={player.playerId} className="player-item">
                <span>{player.username}</span>
                {player.isYou && <span className="badge">You</span>}
              </div>
            ))}
          </div>
        </div>
        <ChatPanel
          messages={chatMessages}
          currentUserId={state?.players.find((player) => player.isYou)?.playerId}
          connected={socketOpen}
          onSend={(text) => (sessionId ? sendChatMessage(wsRef.current, sessionId, text) : false)}
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
