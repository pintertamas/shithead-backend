import { apiFetch, throwForError } from "./client";

export type Card = {
  suit: string;
  value: number;
  rule: string;
  alwaysPlayable: boolean;
};

export type CardSelection = { source: "hand" | "faceUp" | "faceDown"; index: number };

export type PlayerState = {
  playerId: string;
  username: string;
  handCount: number;
  faceUp: Card[];
  faceDownCount: number;
  isYou: boolean;
  hand?: Card[];
  eloScore: number;
  ready: boolean;
};

export type GameEventType =
  | "PLAYED"
  | "PLAYED_AGAIN"
  | "REVERSED"
  | "BURNED"
  | "PICKED_UP"
  | "FAILED_FLIP"
  | "FAILED_PLAY"
  | "READY"
  | "OUT"
  | "FINISHED";

/** One activity-feed entry. Games created before the feed existed have no `events` list at all. */
export type GameEvent = {
  seq: number;
  type: GameEventType;
  playerId: string;
  username: string;
  cards: Card[];
  count: number;
  ts: number;
};

/** Relayed session chat. Only present on the WebSocket; never stored and never returned by REST. */
export type ChatMessage = {
  type: "chat";
  userId: string;
  username: string;
  text: string;
  ts: number;
};

/** Relayed nudge (the fart sound) to everyone in the game. Only present on the WebSocket and never stored. */
export type NudgeMessage = {
  type: "nudge";
  userId: string;
  username: string;
  ts: number;
};

export type GameStateView = {
  sessionId: string;
  started: boolean;
  starting: boolean;
  setupComplete: boolean;
  finished: boolean;
  currentPlayerId: string | null;
  shitheadId: string | null;
  isOwner: boolean;
  deckCount: number;
  discardCount: number;
  discardPile: Card[];
  allowMixedHandAndFaceUpWhenDeckEmpty: boolean;
  allowFailedFaceUpPlay: boolean;
  /** Whether this game has voice chat (LiveKit) turned on. Older servers omit it. */
  voiceEnabled?: boolean;
  revealedCard?: Card | null;
  players: PlayerState[];
  events?: GameEvent[];
};

export type CreateGameConfig = {
  allowMixedHandAndFaceUpWhenDeckEmpty: boolean;
  allowFailedFaceUpPlay: boolean;
  voiceEnabled: boolean;
  decksCount: 1 | 2;
  burnCount: 4 | 6;
  cardRules: Record<string, string>;
  alwaysPlayable: number[];
  canPlayAgain: number[];
};

export async function createGame(token: string, config: CreateGameConfig) {
  const res = await apiFetch("/create-game", token, { method: "POST", body: JSON.stringify({ config }) });
  await throwForError(res, "create the game");
  return res.json() as Promise<{ sessionId: string }>;
}

export async function joinGame(token: string, sessionId: string) {
  const res = await apiFetch("/join-game", token, {
    method: "POST",
    body: JSON.stringify({ sessionId })
  });
  await throwForError(res, "join the game");
}

export async function startGame(token: string, sessionId: string, phase?: "prepare" | "start") {
  const res = await apiFetch("/start-game", token, {
    method: "POST",
    body: JSON.stringify({ sessionId, ...(phase ? { phase } : {}) })
  });
  await throwForError(res, "start the game");
}

export async function leaveGame(token: string, sessionId: string) {
  const res = await apiFetch("/leave-game", token, {
    method: "POST",
    body: JSON.stringify({ sessionId })
  });
  await throwForError(res, "leave the game");
}

export async function fetchState(token: string, sessionId: string) {
  const res = await apiFetch(`/state/${sessionId}`, token, { method: "GET" });
  await throwForError(res, "load game state");
  return res.json() as Promise<GameStateView>;
}

export function openGameSocket(sessionId: string, token: string) {
  const url = new URL(import.meta.env.VITE_WS_BASE_URL);
  if (url.pathname === "/" || url.pathname === "") url.pathname = "/$default";
  url.searchParams.set("game_session_id", sessionId);
  url.searchParams.set("token", token);
  return new WebSocket(url.toString());
}
