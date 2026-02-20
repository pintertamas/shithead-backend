import { apiFetch } from "./client";

export type Card = {
  suit: string;
  value: number;
  rule: string;
  alwaysPlayable: boolean;
};

export type PlayerState = {
  playerId: string;
  username: string;
  handCount: number;
  faceUp: Card[];
  faceDownCount: number;
  isYou: boolean;
  hand?: Card[];
};

export type GameStateView = {
  sessionId: string;
  started: boolean;
  finished: boolean;
  currentPlayerId: string | null;
  shitheadId: string | null;
  isOwner: boolean;
  deckCount: number;
  discardCount: number;
  discardPile: Card[];
  players: PlayerState[];
};

export async function createGame(token: string) {
  const res = await apiFetch("/create-game", token, { method: "POST" });
  if (!res.ok) throw new Error("Failed to create game");
  return res.json() as Promise<{ sessionId: string }>;
}

export async function joinGame(token: string, sessionId: string) {
  const res = await apiFetch("/join-game", token, {
    method: "POST",
    body: JSON.stringify({ sessionId })
  });
  if (!res.ok) throw new Error("Failed to join game");
}

export async function startGame(token: string, sessionId: string) {
  const res = await apiFetch("/start-game", token, {
    method: "POST",
    body: JSON.stringify({ sessionId })
  });
  if (!res.ok) throw new Error("Failed to start game");
}

export async function fetchState(token: string, sessionId: string) {
  const res = await apiFetch(`/state/${sessionId}`, token, { method: "GET" });
  if (!res.ok) throw new Error("Failed to fetch state");
  return res.json() as Promise<GameStateView>;
}

