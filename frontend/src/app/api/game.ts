import { apiFetch, throwForError, wsBaseUrl } from "./client";

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
  revealedCard?: Card | null;
  players: PlayerState[];
};

export type CreateGameConfig = {
  allowMixedHandAndFaceUpWhenDeckEmpty: boolean;
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
  const url = new URL(wsBaseUrl());
  if (url.pathname === "/" || url.pathname === "") url.pathname = "/$default";
  url.searchParams.set("game_session_id", sessionId);
  url.searchParams.set("token", token);
  return new WebSocket(url.toString());
}
