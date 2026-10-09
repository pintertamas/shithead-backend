import { useAuth } from "../auth/useAuth";

const BACKEND_STORAGE_KEY = "shithead_backend";

export type BackendName = "default" | "go";

/**
 * Which backend the app talks to. `?backend=go` stores "go" in localStorage and
 * `?backend=default` clears it. "go" is only effective when both VITE_*_GO
 * variables are set; otherwise the default backend is used.
 */
export function activeBackend(): BackendName {
  try {
    const requested = new URLSearchParams(window.location.search).get("backend");
    if (requested === "go") window.localStorage.setItem(BACKEND_STORAGE_KEY, "go");
    if (requested === "default") window.localStorage.removeItem(BACKEND_STORAGE_KEY);
    const goConfigured = Boolean(import.meta.env.VITE_API_BASE_URL_GO && import.meta.env.VITE_WS_BASE_URL_GO);
    return goConfigured && window.localStorage.getItem(BACKEND_STORAGE_KEY) === "go" ? "go" : "default";
  } catch {
    return "default";
  }
}

export function apiBaseUrl(): string {
  return activeBackend() === "go" ? import.meta.env.VITE_API_BASE_URL_GO : import.meta.env.VITE_API_BASE_URL;
}

export function wsBaseUrl(): string {
  return activeBackend() === "go" ? import.meta.env.VITE_WS_BASE_URL_GO : import.meta.env.VITE_WS_BASE_URL;
}

export function apiFetch(path: string, token: string, options: RequestInit = {}) {
  return fetch(`${apiBaseUrl()}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(options.headers || {}),
      Authorization: `Bearer ${token}`
    }
  });
}

export async function throwForError(response: Response, action: string): Promise<void> {
  if (response.ok) return;
  let serverMessage: string | null = null;
  try {
    const body = await response.clone().json() as { message?: unknown; error?: unknown };
    serverMessage = typeof body.message === "string" ? body.message
      : typeof body.error === "string" ? body.error : null;
  } catch {
    // Fall back to a status-based message when the response has no JSON body.
  }
  if (serverMessage) throw new ApiError(serverMessage, response.status);
  const explanation = response.status === 401 || response.status === 403
    ? "Please sign in again and check that you have access."
    : response.status === 404
      ? "This game may have ended or no longer exists."
      : response.status === 409
        ? "That action is not available in the current game state."
        : response.status === 400
          ? "Please check the information and try again."
        : response.status === 429
          ? "The game is receiving too many requests. Please wait a moment and try again."
          : "The game service is having trouble. Please try again in a moment.";
  throw new ApiError(`Couldn't ${action}. ${explanation}`, response.status);
}

export class ApiError extends Error {
  constructor(message: string, public readonly status: number) {
    super(message);
    this.name = "ApiError";
  }
}

export function useApi() {
  const { token } = useAuth();
  return {
    get: (path: string) => apiFetch(path, token),
    post: (path: string, body?: unknown) =>
      apiFetch(path, token, {
        method: "POST",
        body: body ? JSON.stringify(body) : "{}"
      })
  };
}

