import type { ChatMessage } from "../api/game";

/** Matches the server-side limit in ChatMessageValidator. */
export const CHAT_MAX_LENGTH = 300;
/** In-memory history per screen; chat is intentionally not persisted. */
export const CHAT_HISTORY_LIMIT = 200;

export function appendChatMessage(list: ChatMessage[], message: ChatMessage): ChatMessage[] {
  return [...list, message].slice(-CHAT_HISTORY_LIMIT);
}

/** Returns false when the socket is not open so the caller can show the offline state. */
export function sendChatMessage(socket: WebSocket | null, sessionId: string, text: string): boolean {
  if (!socket || socket.readyState !== WebSocket.OPEN) return false;
  socket.send(JSON.stringify({ action: "chat", sessionId, text }));
  return true;
}
