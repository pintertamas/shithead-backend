import { useEffect, useLayoutEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { createPortal } from "react-dom";
import type { ChatMessage } from "../api/game";
import { CHAT_MAX_LENGTH } from "../lib/sessionChat";
import { useVoiceSession } from "../lib/voice";
import Icon from "./Icon";
import VoicePanel from "./VoicePanel";
import "../styles/feed.css";
import "../styles/chat-layout.css";
import "../styles/scrollbars.css";

/** Phones get a floating button that opens the chat as a bottom sheet. Wider screens keep the chat always open. */
const PHONE_QUERY = "(max-width: 700px)";
/** Within this many pixels of the bottom, the list counts as scrolled to the bottom. */
const BOTTOM_THRESHOLD_PX = 24;

function voiceDotLabel(dot: "on" | "muted" | "pending"): string {
  if (dot === "on") return "connected, microphone on";
  if (dot === "muted") return "connected, microphone muted";
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

/** Keeps the bottom sheet above the on-screen keyboard by tracking the visual viewport. */
function useKeyboardInset(active: boolean): number {
  const [inset, setInset] = useState(0);
  useEffect(() => {
    const viewport = window.visualViewport;
    if (!active || !viewport) {
      setInset(0);
      return undefined;
    }
    const update = () => setInset(Math.max(0, Math.round(window.innerHeight - viewport.height - viewport.offsetTop)));
    update();
    viewport.addEventListener("resize", update);
    viewport.addEventListener("scroll", update);
    return () => {
      viewport.removeEventListener("resize", update);
      viewport.removeEventListener("scroll", update);
    };
  }, [active]);
  return inset;
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
};

/** Session chat. Messages live in React state only, so a refresh clears them by design. */
export default function ChatPanel({ messages, currentUserId, connected, onSend, voiceEnabled, sessionId, players, finished }: Props) {
  const phone = useIsPhone();
  // Only the phone popup has an open state. The inline panel on wider screens is always shown.
  const [sheetOpen, setSheetOpen] = useState(false);
  const [draft, setDraft] = useState("");
  const listRef = useRef<HTMLDivElement>(null);
  const fabRef = useRef<HTMLButtonElement>(null);
  const sheetRef = useRef<HTMLElement>(null);
  const stickToBottom = useRef(true);
  const unread = useUnreadCount(messages, !phone || sheetOpen, currentUserId);
  const keyboardInset = useKeyboardInset(phone && sheetOpen);
  // Owns the voice connection's lifecycle for this game. The chat is always mounted on the room and the table,
  // so this keeps working while the phone sheet is closed.
  const voiceGameId = voiceEnabled && sessionId ? sessionId : undefined;
  const voice = useVoiceSession(voiceGameId, Boolean(finished));
  const voiceLive = voice.status === "connected" || voice.status === "connecting" || voice.status === "reconnecting";
  const voiceDot = !voiceLive ? null : voice.status === "connected" ? (voice.micOn ? "on" : "muted") : "pending";
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
          style={{ paddingBottom: keyboardInset }}
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
