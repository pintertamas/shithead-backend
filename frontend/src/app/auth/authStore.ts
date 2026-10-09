// One shared auth state for the whole app: a module-level snapshot of localStorage `shithead_auth`, exposed to React
// through useSyncExternalStore, with a single refresh timer and at most one token refresh in flight.

const STORAGE_KEY = "shithead_auth";
const COGNITO_DOMAIN = import.meta.env.VITE_COGNITO_DOMAIN;
const COGNITO_CLIENT_ID = import.meta.env.VITE_COGNITO_CLIENT_ID;

/** Refresh this long before the ID token expires. */
const REFRESH_AHEAD_MS = 120_000;
/** getFreshToken() refreshes first when the ID token has less than this left. */
const FRESH_ENOUGH_MS = 60_000;
/** Retry delay after a transient refresh failure (network error, 5xx, 429). */
const RETRY_MS = 30_000;
/** Largest delay setTimeout reliably accepts. */
const MAX_TIMER_MS = 2_000_000_000;

export type AuthState = {
  accessToken: string;
  idToken: string;
  expiresAt: number;
  refreshToken?: string;
};

type RefreshOutcome = "ok" | "rejected" | "transient";

const listeners = new Set<() => void>();
const clearedListeners = new Set<() => void>();

function readStorage(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

function parseStored(raw: string | null): AuthState | null {
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as AuthState;
    if (!parsed.accessToken || !parsed.idToken) return null;
    if (parsed.expiresAt && Date.now() > parsed.expiresAt && !parsed.refreshToken) return null;
    return parsed;
  } catch {
    return null;
  }
}

let current: AuthState | null = parseStored(readStorage());
let timer: number | null = null;
let refreshInFlight: Promise<RefreshOutcome> | null = null;

function emit() {
  listeners.forEach((listener) => listener());
}

function emitCleared() {
  clearedListeners.forEach((listener) => {
    try {
      listener();
    } catch (error) {
      console.error("[auth] onAuthCleared listener failed", error);
    }
  });
}

function setCurrent(next: AuthState | null) {
  current = next;
  scheduleTimer();
  emit();
}

function scheduleTimer(delayMs?: number) {
  if (timer !== null) {
    window.clearTimeout(timer);
    timer = null;
  }
  const state = current;
  if (!state) return;
  const delay = delayMs ?? Math.max(0, state.expiresAt - Date.now() - (state.refreshToken ? REFRESH_AHEAD_MS : 0));
  timer = window.setTimeout(onTimer, Math.min(delay, MAX_TIMER_MS));
}

function onTimer() {
  timer = null;
  checkDue();
}

/** Refreshes now when the token is due, otherwise (re)arms the timer. Shared by the timer and foreground events. */
function checkDue() {
  const state = current;
  if (!state) return;
  if (!state.refreshToken) {
    if (Date.now() >= state.expiresAt) expireSession(state);
    else scheduleTimer();
    return;
  }
  if (state.expiresAt - Date.now() >= REFRESH_AHEAD_MS) {
    scheduleTimer();
    return;
  }
  void refreshWhenDue(state);
}

async function refreshWhenDue(state: AuthState): Promise<void> {
  const outcome = await refreshSession();
  if (outcome === "rejected") expireSession(state);
  else if (outcome === "transient") scheduleTimer(RETRY_MS);
}

/** Clears the session only if it is still the one that failed (a newer login must not be wiped by a stale refresh). */
function expireSession(stale: AuthState) {
  if (current === stale) clearAuth();
}

/** Single flight: every caller shares the one refresh request that is in progress. */
function refreshSession(): Promise<RefreshOutcome> {
  if (!refreshInFlight) {
    refreshInFlight = doRefresh().finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

async function doRefresh(): Promise<RefreshOutcome> {
  const base = current;
  if (!base || !base.refreshToken) return "rejected";

  let response: Response;
  try {
    response = await fetch(`${COGNITO_DOMAIN}/oauth2/token`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "refresh_token",
        client_id: COGNITO_CLIENT_ID,
        refresh_token: base.refreshToken
      }).toString()
    });
  } catch {
    return "transient";
  }
  if (!response.ok) {
    // Cognito answers 400 invalid_grant for a revoked or expired refresh token.
    return response.status === 429 || response.status >= 500 ? "transient" : "rejected";
  }

  let tokens: { id_token?: string; access_token?: string; expires_in?: number; refresh_token?: string };
  try {
    tokens = await response.json();
  } catch {
    return "transient";
  }
  if (!tokens.id_token || !tokens.access_token) return "transient";

  // The user signed out or signed in again while the request was in flight: drop this result.
  if (current !== base) return "ok";

  const refreshed: AuthState = {
    idToken: tokens.id_token,
    accessToken: tokens.access_token,
    expiresAt: Date.now() + (tokens.expires_in || 3600) * 1000,
    refreshToken: tokens.refresh_token || base.refreshToken
  };
  try {
    saveAuth(refreshed);
  } catch {
    // Storage is blocked: keep the session in memory so the app keeps working until reload.
    setCurrent(refreshed);
  }
  return "ok";
}

/** Current auth state, or null when signed out. */
export function loadAuth(): AuthState | null {
  return current;
}

export function saveAuth(state: AuthState): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  setCurrent(state);
}

/** Clears the stored session, notifies subscribers and onAuthCleared listeners. */
export function clearAuth(): void {
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    // Storage unavailable: the in-memory state is still cleared below.
  }
  setCurrent(null);
  emitCleared();
}

/**
 * Returns the ID token to send, refreshing first when it expires within 60 seconds. Returns "" when signed out.
 * When the session cannot be renewed it is cleared, which sends the user to /login through the route guard.
 */
export async function getFreshToken(): Promise<string> {
  const state = current;
  if (!state) return "";
  if (state.expiresAt - Date.now() >= FRESH_ENOUGH_MS) return state.idToken;

  if (!state.refreshToken) {
    if (Date.now() >= state.expiresAt) {
      expireSession(state);
      return "";
    }
    return state.idToken;
  }

  const outcome = await refreshSession();
  if (outcome === "rejected") {
    expireSession(state);
    return "";
  }
  // On a transient failure the current token is still sent; a 401 then triggers one more refresh attempt.
  return current?.idToken ?? "";
}

/**
 * Called after a 401: forces one refresh (shared with any refresh in flight). Returns the new ID token to retry with,
 * or null when the request should not be retried.
 */
export async function refreshAfterUnauthorized(): Promise<string | null> {
  const state = current;
  if (!state) return null;
  if (!state.refreshToken) {
    expireSession(state);
    return null;
  }
  const outcome = await refreshSession();
  if (outcome === "rejected") {
    expireSession(state);
    return null;
  }
  return outcome === "ok" ? current?.idToken ?? null : null;
}

/** Refreshes when the token is expired or expires within 2 minutes. Called on foreground events. */
export function refreshIfDue(): void {
  checkDue();
}

/** Subscribes to any change of the auth state. Used with useSyncExternalStore. */
export function subscribeAuth(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function getAuthSnapshot(): AuthState | null {
  return current;
}

/** Runs whenever auth is cleared (logout, refresh failure, expiry, sign-out in another tab). */
export function onAuthCleared(callback: () => void): () => void {
  clearedListeners.add(callback);
  return () => {
    clearedListeners.delete(callback);
  };
}

/** Lets other modules (for example a blocked account) ask subscribers to drop cached user data. */
export function notifyAuthCleared(): void {
  emitCleared();
}

if (typeof window !== "undefined") {
  scheduleTimer();

  // Timers are frozen while the page is in the background, so check the token whenever the app comes back.
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") refreshIfDue();
  });
  window.addEventListener("pageshow", (event) => {
    if (event.persisted) refreshIfDue();
  });
  window.addEventListener("online", refreshIfDue);

  // Keep tabs in sync: a login or logout in another tab updates this one too.
  window.addEventListener("storage", (event) => {
    if (event.key !== STORAGE_KEY && event.key !== null) return;
    const wasSignedIn = current !== null;
    current = parseStored(readStorage());
    scheduleTimer();
    emit();
    if (wasSignedIn && current === null) emitCleared();
  });
}
