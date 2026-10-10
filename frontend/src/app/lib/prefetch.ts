// Intent-based prefetching: when the user is about to open a screen (hover, focus, touch start, or a button scrolls
// into view), start loading its chunk and, where a screen reads cached data, that data too. Chunks come from the same
// dynamic import() that React.lazy uses, so the module is reused. Data goes through queryClient.prefetchQuery, which
// respects each query's staleTime.
import { useEffect, useMemo, useRef, type RefObject } from "react";
import type { QueryClient } from "@tanstack/react-query";
import { loadAuth } from "../auth/authStore";
import { prefetchGames, prefetchLeaderboard, prefetchProfile } from "../data/queries";
import { queryClient } from "../data/queryClient";
import { fetchScreen, type RouteKey } from "../routeModules";

type ConnectionInfo = { saveData?: boolean; effectiveType?: string };

// Repeated intents would otherwise call a screen's API again, so keep data intents to one per window.
const DATA_INTENT_WINDOW_MS = 15_000;
// Screens reached from the lobby, in the order they are fetched while the lobby is idle.
const IDLE_ORDER: RouteKey[] = ["games", "leaderboard", "profile", "config"];

const routeData: Partial<Record<RouteKey, (qc: QueryClient, token: string) => Promise<unknown>>> = {
  games: prefetchGames,
  leaderboard: prefetchLeaderboard,
  profile: prefetchProfile,
};

const lastDataIntent = new Map<RouteKey, number>();

/** False on data saver or slow connections, where prefetching costs the user more than it saves. */
export function shouldPrefetch(): boolean {
  if (typeof navigator === "undefined") return false;
  const connection = (navigator as Navigator & { connection?: ConnectionInfo }).connection;
  if (!connection) return true;
  if (connection.saveData) return false;
  return connection.effectiveType !== "2g" && connection.effectiveType !== "slow-2g";
}

function prefetchChunk(key: RouteKey): void {
  // A failed prefetch is harmless: the real navigation loads the chunk again and handles errors.
  fetchScreen(key).catch(() => {});
}

function prefetchData(key: RouteKey): void {
  const run = routeData[key];
  if (!run) return;
  const token = loadAuth()?.idToken ?? "";
  if (!token) return;
  const now = Date.now();
  if (now - (lastDataIntent.get(key) ?? 0) < DATA_INTENT_WINDOW_MS) return;
  lastDataIntent.set(key, now);
  void run(queryClient, token);
}

/** Starts the chunk (and data, where the screen has any) for each route key. Does nothing on constrained networks. */
export function prefetchRoutes(keys: readonly RouteKey[]): void {
  if (!shouldPrefetch()) return;
  for (const key of keys) {
    prefetchChunk(key);
    prefetchData(key);
  }
}

type IdleHandle = number | ReturnType<typeof setTimeout>;

/**
 * Loads the chunks of likely next screens one at a time while the browser is idle. Chunks only, no data, so idle
 * time never wakes the API. Returns a cancel function for unmount.
 */
export function startIdleChunkPrefetch(keys: readonly RouteKey[] = IDLE_ORDER): () => void {
  if (!shouldPrefetch()) return () => {};
  const hasIdle = typeof window.requestIdleCallback === "function";
  let cancelled = false;
  let handle: IdleHandle | null = null;
  let next = 0;

  const later = (task: () => void) => {
    handle = hasIdle
      ? window.requestIdleCallback(task, { timeout: 3_000 })
      : setTimeout(task, 300); // Safari has no requestIdleCallback.
  };

  const step = () => {
    if (cancelled || next >= keys.length) return;
    const key = keys[next++];
    fetchScreen(key).catch(() => {}).finally(() => later(step));
  };

  later(step);
  return () => {
    cancelled = true;
    if (handle === null) return;
    if (hasIdle) window.cancelIdleCallback(handle as number);
    else clearTimeout(handle as ReturnType<typeof setTimeout>);
  };
}

export type PrefetchHandlers<T extends HTMLElement> = {
  ref: RefObject<T>;
  onPointerEnter: () => void;
  onFocus: () => void;
  onPointerDown: () => void;
  onTouchStart: () => void;
};

/**
 * Prefetch triggers for an element. Hover and focus cover desktop, pointerdown and touchstart cover touch screens
 * (they arrive roughly 100 to 200 ms before the click). With onView, the keys also start once the element is on screen.
 */
export function usePrefetch<T extends HTMLElement = HTMLElement>(
  keys: readonly RouteKey[],
  options: { onView?: boolean } = {},
): PrefetchHandlers<T> {
  const ref = useRef<T>(null);
  const keyList = keys.join(",");
  const onView = options.onView ?? false;
  const list = useMemo(() => keyList.split(",").filter(Boolean) as RouteKey[], [keyList]);

  useEffect(() => {
    const element = ref.current;
    if (!onView || !element || typeof IntersectionObserver === "undefined") return;
    const observer = new IntersectionObserver((entries) => {
      if (!entries.some((entry) => entry.isIntersecting)) return;
      observer.disconnect();
      prefetchRoutes(list);
    }, { threshold: 0.1 });
    observer.observe(element);
    return () => observer.disconnect();
  }, [list, onView]);

  const intent = () => prefetchRoutes(list);
  return { ref, onPointerEnter: intent, onFocus: intent, onPointerDown: intent, onTouchStart: intent };
}
