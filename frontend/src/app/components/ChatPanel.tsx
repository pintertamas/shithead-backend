import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import type { ChatMessage } from "../api/game";
import { CHAT_MAX_LENGTH } from "../lib/sessionChat";
import "../styles/feed.css";

type Props = {
  messages: ChatMessage[];
  currentUserId?: string;
  connected: boolean;
  onSend: (text: string) => boolean;
};

/** Session chat. Messages live in React state only, so a refresh clears them by design. */
export default function ChatPanel({ messages, currentUserId, connected, onSend }: Props) {
  const [open, setOpen] = useState(true);
  const [draft, setDraft] = useState("");
  const [seenCount, setSeenCount] = useState(messages.length);
  const listRef = useRef<HTMLDivElement>(null);
  const unread = open ? 0 : Math.max(0, messages.length - seenCount);

  useEffect(() => {
    if (open) setSeenCount(messages.length);
  }, [open, messages.length]);

  useEffect(() => {
    const list = listRef.current;
    if (open && list) list.scrollTop = list.scrollHeight;
  }, [open, messages.length]);

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const text = draft.trim();
    if (!text || !connected) return;
    if (onSend(text)) setDraft("");
  };

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
        <span className="game-feed-chevron" aria-hidden="true">{open ? "▴" : "▾"}</span>
      </button>
      {open && (
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
      )}
    </section>
  );
}
