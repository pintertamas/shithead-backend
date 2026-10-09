import { defaultShouldDehydrateQuery, QueryClient, type Query } from "@tanstack/react-query";
import { createSyncStoragePersister } from "@tanstack/query-sync-storage-persister";
import { isPersistedQueryKey } from "./keys";

// Keep in step with frontend/package.json "version": a different buster discards the persisted cache on load.
export const APP_VERSION = "0.1.0";
const SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000;
const CACHE_STORAGE_KEY = "shithead_query_cache";
const CACHE_OWNER_KEY = "shithead_query_owner";

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 0,
      // Must cover maxAge, otherwise inactive queries are garbage collected before they can be persisted.
      gcTime: SEVEN_DAYS_MS,
      retry: 1,
      refetchOnWindowFocus: true,
      structuralSharing: true,
    },
  },
});

// Every storage call is guarded: storage can be blocked, full or missing (private windows, cleared site data).
const safeStorage = {
  getItem(key: string): string | null {
    try { return window.localStorage.getItem(key); } catch { return null; }
  },
  setItem(key: string, value: string): void {
    try { window.localStorage.setItem(key, value); } catch { /* quota exceeded or storage disabled */ }
  },
  removeItem(key: string): void {
    try { window.localStorage.removeItem(key); } catch { /* storage unavailable */ }
  },
};

const persister = createSyncStoragePersister({
  storage: safeStorage,
  key: CACHE_STORAGE_KEY,
  throttleTime: 1000,
});

export const persistOptions = {
  persister,
  maxAge: SEVEN_DAYS_MS,
  buster: APP_VERSION,
  dehydrateOptions: {
    shouldDehydrateQuery: (query: Query) =>
      defaultShouldDehydrateQuery(query) && isPersistedQueryKey(query.queryKey),
  },
};

function readOwner(): string | null {
  return safeStorage.getItem(CACHE_OWNER_KEY);
}

export function writeCacheOwner(sub: string | null): void {
  if (sub) safeStorage.setItem(CACHE_OWNER_KEY, sub);
  else safeStorage.removeItem(CACHE_OWNER_KEY);
}

// Startup: drop the persisted cache unless it belongs to the signed-in user (or nobody is signed in).
export function syncCacheOwner(sub: string | null): void {
  if (!sub || readOwner() !== sub) safeStorage.removeItem(CACHE_STORAGE_KEY);
  writeCacheOwner(sub);
}

// Sign-out, account switch or block: remove the persisted cache and everything in memory.
export function clearAppCache(): void {
  safeStorage.removeItem(CACHE_STORAGE_KEY);
  queryClient.clear();
}
