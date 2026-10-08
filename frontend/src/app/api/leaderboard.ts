import { apiFetch, throwForError } from "./client";

export type LeaderboardEntry = {
  userId: string;
  username: string;
  eloScore: number;
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

