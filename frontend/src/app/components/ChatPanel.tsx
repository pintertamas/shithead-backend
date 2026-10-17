import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { FormEvent } from "react";
import { createPortal } from "react-dom";
import type { ChatMessage } from "../api/game";
import { CHAT_MAX_LENGTH } from "../lib/sessionChat";
import { useAuth } from "../auth/useAuth";
import { useVoiceSession, VOICE_AFTER_GAME_MS } from "../lib/voice";
import Icon from "./Icon";
import VoicePanel from "./VoicePanel";
import "../styles/feed.css";
import "../styles/chat-layout.css";
import "../styles/scrollbars.css";

/** Phones get a floating button that opens the chat as a bottom sheet. Wider screens keep the chat always open. */
const PHONE_QUERY = "(max-width: 700px)";
/** Within this many pixels of the bottom, the list counts as scrolled to the bottom. */
const BOTTOM_THRESHOLD_PX = 24;

type VoiceDot = "on" | "muted" | "pending" | "blocked";

function voiceDotLabel(dot: VoiceDot): string {
  if (dot === "on") return "connected, microphone on";
  if (dot === "muted") return "connected, microphone muted";
  if (dot === "blocked") return "connected, sound blocked, tap to enable sound";
  return "connecting";
}

function matchesPhone(): boolean {
  try {
    return window.matchMedia(PHONE_QUERY).matches;
  } catch {
    return false;
  }
}

function useIsPhone(): boolean {
  const [phone, setPhone] = useState(matchesPhone);
  useEffect(() => {
    let query: MediaQueryList;
    try {
      query = window.matchMedia(PHONE_QUERY);
    } catch {
      return undefined;
    }
    const onChange = () => setPhone(query.matches);
    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, []);
  return phone;
}

/**
 * Counts incoming messages received while the chat is closed. Own messages never count.
 * Works from message identity, because the parent caps the list length and a length diff would stall at the cap.
 */
function useUnreadCount(messages: ChatMessage[], open: boolean, currentUserId?: string): number {
  const [unread, setUnread] = useState(0);
  const lastSeen = useRef<ChatMessage | undefined>(messages[messages.length - 1]);

  useEffect(() => {
    const latest = messages[messages.length - 1];
    if (open) {
      lastSeen.current = latest;
      setUnread(0);
      return;
    }
    if (latest === lastSeen.current) return;
    const seenAt = lastSeen.current ? messages.indexOf(lastSeen.current) : -1;
    const fresh = seenAt >= 0 ? messages.slice(seenAt + 1) : messages;
    lastSeen.current = latest;
    const incoming = fresh.filter((message) => message.userId !== currentUserId).length;
    if (incoming > 0) setUnread((count) => count + incoming);
  }, [messages, open, currentUserId]);

  return unread;
}

/** True when the element (below the sheet) is a scroller that can still move in the finger's direction. */
function canScrollWithin(target: EventTarget | null, boundary: Element, dy: number): boolean {
  let node = target instanceof Element ? target : null;
  while (node && node !== boundary.parentElement) {
    if (node instanceof HTMLElement && node.scrollHeight > node.clientHeight + 1) {
      const overflowY = window.getComputedStyle(node).overflowY;
      if (overflowY === "auto" || overflowY === "scroll") {
        // Finger moving down (dy > 0) scrolls content towards the top.
        if (dy > 0 && node.scrollTop > 0) return true;
        if (dy < 0 && node.scrollTop + node.clientHeight < node.scrollHeight - 1) return true;
      }
    }
    node = node.parentElement;
  }
  return false;
}

/**
 * While the phone sheet is open, the sheet is a fixed box that mirrors the visual viewport (the area above the
 * on-screen keyboard) and nothing behind it can move.
 * - --vv-top / --vvh on the root element carry the visual viewport's offsetTop and height. They are written
 *   synchronously on every visualViewport resize/scroll, window scroll/resize and input focus change (plus a few
 *   timed re-reads, because iOS pans the viewport during the keyboard animation without always firing events).
 * - The "chat-sheet-open" class on the root locks the document (overflow hidden), and "chat-kb" is set while a
 *   keyboard covers part of the screen.
 * - iOS ignores overflow:hidden for touch panning, so a non-passive touchmove handler cancels every gesture that is not
 *   scrolling a scroller that can still move in that direction (no chaining to the page), and window scroll is put
 *   back to where it was when the sheet opened (a focused input makes iOS scroll the layout viewport).
 * Without the visual viewport API the variables stay unset and the CSS fallbacks (full screen) apply.
 */
function useChatSheetLock(active: boolean): void {
  useEffect(() => {
    if (!active) return undefined;
    const root = document.documentElement;
    const viewport = window.visualViewport;
    const savedX = window.scrollX;
    const savedY = window.scrollY;
    root.classList.add("chat-sheet-open");

    const apply = () => {
      if (!viewport) return;
      const top = Math.max(0, viewport.offsetTop);
      const height = viewport.height;
      root.style.setProperty("--vv-top", `${top}px`);
      root.style.setProperty("--vvh", `${height}px`);
      const keyboard = window.innerHeight - height - top > 80;
      root.classList.toggle("chat-kb", keyboard);
    };
    const holdPage = () => {
      if (window.scrollX !== savedX || window.scrollY !== savedY) window.scrollTo(savedX, savedY);
    };
    const onViewportChange = () => {
      apply();
      holdPage();
    };
    const timers: number[] = [];
    const onFocusChange = () => {
      onViewportChange();
      for (const delay of [50, 150, 350, 700]) timers.push(window.setTimeout(onViewportChange, delay));
    };

    let startY = 0;
    const onTouchStart = (event: TouchEvent) => {
      startY = event.touches[0]?.clientY ?? 0;
    };
    const onTouchMove = (event: TouchEvent) => {
      if (!event.cancelable) return;
      const sheet = document.querySelector(".chat-sheet");
      const target = event.target;
      if (event.touches.length > 1) {
        event.preventDefault();
        return;
      }
      if (target instanceof HTMLInputElement) return;
      const dy = (event.touches[0]?.clientY ?? startY) - startY;
      if (sheet && target instanceof Node && sheet.contains(target) && canScrollWithin(target, sheet, dy)) return;
      event.preventDefault();
    };

    apply();
    viewport?.addEventListener("resize", onViewportChange);
    viewport?.addEventListener("scroll", onViewportChange);
    window.addEventListener("scroll", onViewportChange);
    window.addEventListener("resize", onViewportChange);
    document.addEventListener("focusin", onFocusChange);
    document.addEventListener("focusout", onFocusChange);
    document.addEventListener("touchstart", onTouchStart, { passive: true, capture: true });
    document.addEventListener("touchmove", onTouchMove, { passive: false, capture: true });
    const poll = window.setInterval(onViewportChange, 300);
    return () => {
      viewport?.removeEventListener("resize", onViewportChange);
      viewport?.removeEventListener("scroll", onViewportChange);
      window.removeEventListener("scroll", onViewportChange);
      window.removeEventListener("resize", onViewportChange);
      document.removeEventListener("focusin", onFocusChange);
      document.removeEventListener("focusout", onFocusChange);
      document.removeEventListener("touchstart", onTouchStart, { capture: true });
      document.removeEventListener("touchmove", onTouchMove, { capture: true });
      window.clearInterval(poll);
      for (const timer of timers) window.clearTimeout(timer);
      root.classList.remove("chat-sheet-open", "chat-kb");
      root.style.removeProperty("--vv-top");
      root.style.removeProperty("--vvh");
      holdPage();
    };
  }, [active]);
}

type Props = {
  messages: ChatMessage[];
  currentUserId?: string;
  connected: boolean;
  onSend: (text: string) => boolean;
  /** Voice controls appear in the chat when the game has voice enabled. */
  voiceEnabled?: boolean;
  sessionId?: string;
  players?: { playerId: string; username: string }[];
  finished?: boolean;
  /** Epoch ms when the game ended; voice stays open VOICE_AFTER_GAME_MS after it. */
  finishedAt?: number | null;
  /** A rematch lobby was started without this player. */
  rematchStarted?: boolean;
  /** Game id of the started rematch; a viewer still in voice is moved into its room. */
  rematchSessionId?: string | null;
};

/** Session chat. Messages live in React state only, so a refresh clears them by design. */
export default function ChatPanel({ messages, currentUserId, connected, onSend, voiceEnabled, sessionId, players, finished, finishedAt, rematchStarted, rematchSessionId }: Props) {
  const { token } = useAuth();
  const phone = useIsPhone();
  // Only the phone popup has an open state. The inline panel on wider screens is always shown.
  const [sheetOpen, setSheetOpen] = useState(false);
  const [draft, setDraft] = useState("");
  const listRef = useRef<HTMLDivElement>(null);
  const fabRef = useRef<HTMLButtonElement>(null);
  const sheetRef = useRef<HTMLElement>(null);
  const stickToBottom = useRef(true);
  const unread = useUnreadCount(messages, !phone || sheetOpen, currentUserId);
  useChatSheetLock(phone && sheetOpen);
  // Owns the voice connection's lifecycle for this game. The chat is always mounted on the room and the table,
  // so this keeps working while the phone sheet is closed.
  const voiceGameId = voiceEnabled && sessionId ? sessionId : undefined;
  // Computed once per finish so the deadline does not drift between renders. A finishedAt in the future (clock
  // skew) or a missing one counts as "now".
  const leaveAtMs = useMemo(() => {
    if (!finished) return null;
    const now = Date.now();
    const base = finishedAt == null || finishedAt > now ? now : finishedAt;
    return base + VOICE_AFTER_GAME_MS;
  }, [finished, finishedAt]);
  const voice = useVoiceSession(voiceGameId, Boolean(finished), leaveAtMs, rematchStarted, rematchSessionId, token);
  const voiceLive = voice.status === "connected" || voice.status === "connecting" || voice.status === "reconnecting";
  const voiceDot: VoiceDot | null = !voiceLive
    ? null
    : voice.audioBlocked
      ? "blocked"
      : voice.status === "connected"
        ? (voice.micOn ? "on" : "muted")
        : "pending";
  const voiceNode = voiceGameId ? (
    <VoicePanel sessionId={voiceGameId} players={players ?? []} finished={Boolean(finished)} />
  ) : null;

  useEffect(() => {
    setSheetOpen(false);
  }, [phone]);

  // The newest message keeps the list at the bottom, unless the reader has scrolled up. Own messages always jump down.
  useLayoutEffect(() => {
    const list = listRef.current;
    if (!list) return;
    const latest = messages[messages.length - 1];
    if (latest && latest.userId === currentUserId) stickToBottom.current = true;
    if (stickToBottom.current) list.scrollTop = list.scrollHeight;
  }, [messages, currentUserId, phone, sheetOpen]);

  const onListScroll = () => {
    const list = listRef.current;
    if (!list) return;
    stickToBottom.current = list.scrollHeight - list.scrollTop - list.clientHeight < BOTTOM_THRESHOLD_PX;
  };

  useEffect(() => {
    if (!phone || !sheetOpen) return undefined;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      setSheetOpen(false);
      fabRef.current?.focus();
    };
    const previousOverflow = document.body.style.overflow;
    document.addEventListener("keydown", closeOnEscape);
    document.body.style.overflow = "hidden";
    sheetRef.current?.focus();
    return () => {
      document.removeEventListener("keydown", closeOnEscape);
      document.body.style.overflow = previousOverflow;
    };
  }, [phone, sheetOpen]);

  const closeSheet = () => {
    setSheetOpen(false);
    fabRef.current?.focus();
  };

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const text = draft.trim();
    if (!text || !connected) return;
    if (onSend(text)) setDraft("");
  };

  const body = (
    <>
      <div className="chat-list themed-scroll" role="log" ref={listRef} onScroll={onListScroll}>
        {messages.length === 0 && <p className="chat-empty">No messages yet. Say hello.</p>}
        {messages.map((message, index) => (
          <div
            key={`${message.ts}:${message.userId}:${index}`}
            className={`chat-message${message.userId === currentUserId ? " own" : ""}`}
          >
            <span className="chat-author">{message.username}</span>
            <span className="chat-text">{message.text}</span>
          </div>
        ))}
      </div>
      <p className="chat-hint">{connected ? "" : "Chat is offline while the live connection is closed."}</p>
      <form className="chat-form" onSubmit={submit}>
        <input
          className="chat-input"
          type="text"
          value={draft}
          maxLength={CHAT_MAX_LENGTH}
          placeholder={connected ? "Message the table" : "Reconnect to chat"}
          aria-label="Chat message"
          disabled={!connected}
          enterKeyHint="send"
          autoComplete="off"
          onChange={(event) => setDraft(event.target.value)}
        />
        <button type="submit" className="button chat-send" disabled={!connected || draft.trim() === ""}>
          Send
        </button>
      </form>
    </>
  );

  if (!phone) {
    return (
      <section className="chat-panel glass" aria-label="Session chat">
        <div className="chat-heading">Chat</div>
        {voiceNode}
        {body}
      </section>
    );
  }

  return (
    <>
      {createPortal(
        <button
          ref={fabRef}
          type="button"
          className="chat-fab"
          aria-haspopup="dialog"
          aria-expanded={sheetOpen}
          aria-label={`${unread > 0 ? `Open chat, ${unread} unread messages` : "Open chat"}${voiceDot ? `, voice ${voiceDotLabel(voiceDot)}` : ""}`}
          onClick={() => setSheetOpen(true)}
        >
          <Icon name="chat" size={22} />
          {unread > 0 && <span className="chat-fab-badge" aria-hidden="true">{unread > 99 ? "99+" : unread}</span>}
          {voiceDot && (
            <span className={`chat-fab-voice is-${voiceDot}`} aria-hidden="true">
              <Icon name={voiceDot === "muted" ? "mic-off" : "mic"} size={11} />
            </span>
          )}
        </button>,
        document.body
      )}
      {sheetOpen && createPortal(
        <div
          className="chat-sheet-backdrop"
          onClick={(event) => { if (event.target === event.currentTarget) closeSheet(); }}
        >
          <section
            ref={sheetRef}
            className="chat-sheet glass"
            role="dialog"
            aria-modal="true"
            aria-label="Session chat"
            tabIndex={-1}
          >
            <header className="chat-sheet-header">
              <h2 className="chat-sheet-title title">Chat</h2>
              <button type="button" className="chat-sheet-close" aria-label="Close chat" onClick={closeSheet}>
                <Icon name="close" />
              </button>
            </header>
            {voiceNode}
            {body}
          </section>
        </div>,
        document.body
      )}
    </>
  );
}
