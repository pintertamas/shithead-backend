import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { createPortal } from "react-dom";
import type { ChatMessage } from "../api/game";
import { CHAT_MAX_LENGTH } from "../lib/sessionChat";
import Icon from "./Icon";
import "../styles/feed.css";
import "../styles/chat-layout.css";

/** Phones get a floating button that opens the chat as a bottom sheet. Wider screens keep the inline panel. */
const PHONE_QUERY = "(max-width: 700px)";

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
};

/** Session chat. Messages live in React state only, so a refresh clears them by design. */
export default function ChatPanel({ messages, currentUserId, connected, onSend }: Props) {
  const phone = useIsPhone();
  // Inline panel starts expanded; the popup starts closed. Switching layouts resets to that default.
  const [open, setOpen] = useState(!phone);
  const [draft, setDraft] = useState("");
  const listRef = useRef<HTMLDivElement>(null);
  const fabRef = useRef<HTMLButtonElement>(null);
  const sheetRef = useRef<HTMLElement>(null);
  const previousPhone = useRef(phone);
  const unread = useUnreadCount(messages, open, currentUserId);
  const keyboardInset = useKeyboardInset(phone && open);

  useEffect(() => {
    if (previousPhone.current === phone) return;
    previousPhone.current = phone;
    setOpen(!phone);
  }, [phone]);

  useEffect(() => {
    const list = listRef.current;
    if (open && list) list.scrollTop = list.scrollHeight;
  }, [open, messages.length, phone]);

  useEffect(() => {
    if (!phone || !open) return undefined;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      setOpen(false);
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
  }, [phone, open]);

  const closeSheet = () => {
    setOpen(false);
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
      <div className="chat-list" role="log" ref={listRef}>
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
      {!connected && <p className="chat-hint">Chat is offline while the live connection is closed.</p>}
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
        <button
          type="button"
          className="chat-toggle"
          aria-expanded={open}
          onClick={() => setOpen((value) => !value)}
        >
          <span>Chat</span>
          {unread > 0 && <span className="chat-unread" aria-label={`${unread} unread messages`}>{unread}</span>}
          <Icon name={open ? "chevron-up" : "chevron-down"} className="game-feed-chevron" />
        </button>
        {open && body}
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
          aria-expanded={open}
          aria-label={unread > 0 ? `Open chat, ${unread} unread messages` : "Open chat"}
          onClick={() => setOpen(true)}
        >
          <Icon name="chat" size={22} />
          {unread > 0 && <span className="chat-fab-badge" aria-hidden="true">{unread > 99 ? "99+" : unread}</span>}
        </button>,
        document.body
      )}
      {open && createPortal(
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
            {body}
          </section>
        </div>,
        document.body
      )}
    </>
  );
}
