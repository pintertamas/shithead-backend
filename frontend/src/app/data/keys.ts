// Query keys are namespaced per user: ['u', <sub>, ...].
export const keys = {
  profile: (sub: string) => ["u", sub, "profile"] as const,
  leaderboardTop: (sub: string, limit: number) => ["u", sub, "leaderboard", "top", limit] as const,
  leaderboardSession: (sub: string, sessionId: string) => ["u", sub, "leaderboard", "session", sessionId] as const,
  games: (sub: string) => ["u", sub, "games"] as const,
  adminUsers: (sub: string) => ["u", sub, "admin", "users"] as const,
};

// Allow-list of what may be written to localStorage. Admin lists and anything holding game state stay in memory only.
export function isPersistedQueryKey(queryKey: readonly unknown[]): boolean {
  if (queryKey[0] !== "u") return false;
  const area = queryKey[2];
  if (area === "profile" || area === "games") return queryKey.length === 3;
  if (area === "leaderboard") return queryKey[3] === "top" || queryKey[3] === "session";
  return false;
}
