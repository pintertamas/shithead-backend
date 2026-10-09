import { apiFetch, throwForError } from "./client";

export type OpenGame = {
  sessionId: string;
  ownerName: string;
  playerCount: number;
  maxPlayers: number;
  status: "waiting" | "in_progress";
  decksCount: number;
  createdAt: string | null;
};

export async function fetchOpenGames(token: string): Promise<OpenGame[]> {
  const response = await apiFetch("/games", token, { method: "GET" });
  await throwForError(response, "load open games");
  return response.json() as Promise<OpenGame[]>;
}
