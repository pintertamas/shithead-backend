import { useEffect, useState } from "react";
import "../styles/chat-bubble.css";

/** How long a bubble stays above the seat before it fades out. */
export const BUBBLE_MS = 6000;
/** Longest text a bubble shows, ellipsis included. The full message stays in the chat list. */
export const BUBBLE_MAX_CHARS = 40;

export function clampBubbleText(text: string): string {
  const chars = Array.from(text.trim());
  if (chars.length <= BUBBLE_MAX_CHARS) return chars.join("");
  return `${chars.slice(0, BUBBLE_MAX_CHARS - 1).join("").trimEnd()}…`;
}

type Props = {
  text: string;
};

/**
 * Speech bubble above a seat. Mount it with a key that changes per message so a newer message restarts it.
 * It removes itself after BUBBLE_MS; the CSS animation fades it out over that time.
 */
export default function ChatBubble({ text }: Props) {
  const [visible, setVisible] = useState(true);

  useEffect(() => {
    const timer = window.setTimeout(() => setVisible(false), BUBBLE_MS);
    return () => window.clearTimeout(timer);
  }, []);

  if (!visible) return null;
  return (
    <div className="chat-bubble" aria-hidden="true">
      <span className="chat-bubble-text">{clampBubbleText(text)}</span>
    </div>
  );
}
