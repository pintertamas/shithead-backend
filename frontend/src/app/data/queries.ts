import { queryOptions, useMutation, useQuery, type QueryClient } from "@tanstack/react-query";
import { fetchProfile, updateProfile, type UserProfile } from "../api/profile";
import { fetchGlobalLeaderboard, fetchSessionLeaderboard, type LeaderboardEntry } from "../api/leaderboard";
import { fetchOpenGames, type OpenGame } from "../api/games";
import { fetchAdminUsers, setUserBlocked, type AdminUser } from "../api/admin";
import { keys } from "./keys";
import { queryClient } from "./queryClient";
import { userKeyFromToken } from "./userKey";

export const TTL = {
  profile: 5 * 60_000,
  globalLeaderboard: 60_000,
  sessionLeaderboard: 5 * 60_000,
  games: 0,
  adminUsers: 30_000,
} as const;

// Games refresh while the Games screen is mounted; react-query pauses the interval while the tab is hidden.
export const GAMES_REFRESH_MS = 5_000;

// The API accepts 1..100. The lobby uses the same 100-row query and keeps the top 3 via select,
// so the lobby and the full leaderboard share one request.
export const GLOBAL_LEADERBOARD_LIMIT = 100;

const topThree = (rows: LeaderboardEntry[]) => rows.slice(0, 3);

export function profileQueryOptions(token: string) {
  const sub = userKeyFromToken(token) ?? "";
  return queryOptions({
    queryKey: keys.profile(sub),
    queryFn: () => fetchProfile(token),
    staleTime: TTL.profile,
  });
}

export function globalLeaderboardQueryOptions(token: string, limit = GLOBAL_LEADERBOARD_LIMIT) {
  const sub = userKeyFromToken(token) ?? "";
  return queryOptions({
    queryKey: keys.leaderboardTop(sub, limit),
    queryFn: () => fetchGlobalLeaderboard(token, limit),
    staleTime: TTL.globalLeaderboard,
  });
}

export function sessionLeaderboardQueryOptions(token: string, sessionId: string) {
  const sub = userKeyFromToken(token) ?? "";
  return queryOptions({
    queryKey: keys.leaderboardSession(sub, sessionId),
    queryFn: () => fetchSessionLeaderboard(token, sessionId),
    staleTime: TTL.sessionLeaderboard,
  });
}

export function gamesQueryOptions(token: string) {
  const sub = userKeyFromToken(token) ?? "";
  return queryOptions({
    queryKey: keys.games(sub),
    queryFn: () => fetchOpenGames(token),
    staleTime: TTL.games,
  });
}

export function adminUsersQueryOptions(token: string) {
  const sub = userKeyFromToken(token) ?? "";
  return queryOptions({
    queryKey: keys.adminUsers(sub),
    queryFn: () => fetchAdminUsers(token),
    staleTime: TTL.adminUsers,
    gcTime: 10 * 60_000,
  });
}

export function useProfileQuery(token: string) {
  return useQuery({ ...profileQueryOptions(token), enabled: Boolean(userKeyFromToken(token)) });
}

export function useGlobalLeaderboardQuery<T = LeaderboardEntry[]>(
  token: string,
  select?: (rows: LeaderboardEntry[]) => T,
) {
  return useQuery({
    ...globalLeaderboardQueryOptions(token),
    enabled: Boolean(userKeyFromToken(token)),
    select,
  });
}

// Lobby top 3, derived from the shared 100-row global leaderboard query.
export function useLobbyTopQuery(token: string) {
  return useGlobalLeaderboardQuery(token, topThree);
}

export function useSessionLeaderboardQuery(token: string, sessionId: string | undefined) {
  return useQuery({
    ...sessionLeaderboardQueryOptions(token, sessionId ?? ""),
    enabled: Boolean(sessionId && userKeyFromToken(token)),
  });
}

export function useOpenGamesQuery(token: string) {
  return useQuery({
    ...gamesQueryOptions(token),
    enabled: Boolean(userKeyFromToken(token)),
    refetchInterval: GAMES_REFRESH_MS,
    refetchIntervalInBackground: false,
  });
}

export function useAdminUsersQuery(token: string) {
  return useQuery({ ...adminUsersQueryOptions(token), enabled: Boolean(userKeyFromToken(token)) });
}

export function useUpdateProfileMutation(token: string) {
  return useMutation({
    mutationFn: (username: string) => updateProfile(token, username),
    onSuccess: (profile: UserProfile) => {
      queryClient.setQueryData(profileQueryOptions(token).queryKey, profile);
    },
  });
}

type BlockChange = { userId: string; blocked: boolean };

export function useSetUserBlockedMutation(token: string) {
  const adminKey = adminUsersQueryOptions(token).queryKey;
  return useMutation({
    mutationFn: ({ userId, blocked }: BlockChange) => setUserBlocked(token, userId, blocked),
    onMutate: async ({ userId, blocked }: BlockChange) => {
      await queryClient.cancelQueries({ queryKey: adminKey });
      const previous = queryClient.getQueryData<AdminUser[]>(adminKey);
      queryClient.setQueryData<AdminUser[]>(adminKey, (rows) =>
        rows?.map((row) => (row.userId === userId ? { ...row, blocked } : row)));
      return { previous };
    },
    onError: (_error, _vars, context) => {
      if (context?.previous) queryClient.setQueryData(adminKey, context.previous);
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: adminKey }),
  });
}

// Create, join, and game clearing all change the open games list.
export function invalidateGames(token: string) {
  const sub = userKeyFromToken(token);
  if (!sub) return Promise.resolve();
  return queryClient.invalidateQueries({ queryKey: keys.games(sub) });
}

export function prefetchProfile(qc: QueryClient, token: string) {
  if (!userKeyFromToken(token)) return Promise.resolve();
  return qc.prefetchQuery(profileQueryOptions(token));
}

export function prefetchLeaderboard(qc: QueryClient, token: string) {
  if (!userKeyFromToken(token)) return Promise.resolve();
  return qc.prefetchQuery(globalLeaderboardQueryOptions(token));
}

export function prefetchGames(qc: QueryClient, token: string) {
  if (!userKeyFromToken(token)) return Promise.resolve();
  return qc.prefetchQuery(gamesQueryOptions(token));
}

export function prefetchLobby(qc: QueryClient, token: string) {
  return Promise.all([prefetchProfile(qc, token), prefetchLeaderboard(qc, token)]);
}
