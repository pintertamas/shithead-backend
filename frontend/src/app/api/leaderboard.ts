import { apiFetch, throwForError } from "./client";

export type LeaderboardEntry = {
  userId: string;
  username: string;
  /** Current rating. */
  eloScore: number;
  /** Rating before the session's game, present only on session rows with a recorded Elo change. */
  eloBefore?: number | null;
  /** Rating after the session's game, present together with eloBefore. */
  eloAfter?: number | null;
};

export async function fetchSessionLeaderboard(token: string, sessionId: string) {
  const res = await apiFetch(`/leaderboard/session/${sessionId}`, token, { method: "GET" });
  await throwForError(res, "load the session leaderboard");
  return res.json() as Promise<LeaderboardEntry[]>;
}

export async function fetchGlobalLeaderboard(token: string, limit = 20) {
  const res = await apiFetch(`/leaderboard/top?limit=${limit}`, token, { method: "GET" });
  await throwForError(res, "load the global leaderboard");
  return res.json() as Promise<LeaderboardEntry[]>;
}

