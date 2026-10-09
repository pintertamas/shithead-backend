import { useAuth } from "../auth/useAuth";
import { getFreshToken, notifyAuthCleared, refreshAfterUnauthorized } from "../auth/authStore";
import { ACCOUNT_BLOCKED_MESSAGE, isAccountBlocked, markAccountBlocked } from "../auth/accountBlocked";

const API_BASE = import.meta.env.VITE_API_BASE_URL;

/**
 * Sends a request with a token that is not about to expire. On a 401 it forces one refresh and retries once.
 * The `token` argument is kept for call-site compatibility; the shared auth store is the source of truth.
 */
export async function apiFetch(path: string, token: string, options: RequestInit = {}): Promise<Response> {
  const send = (bearer: string) => fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(options.headers || {}),
      Authorization: `Bearer ${bearer}`
    }
  });

  const response = await send(await getFreshToken());
  if (response.status !== 401) return response;

  const refreshedToken = await refreshAfterUnauthorized();
  if (!refreshedToken) return response;
  return send(refreshedToken);
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
  if (response.status === 403 && serverMessage === ACCOUNT_BLOCKED_MESSAGE && !isAccountBlocked()) {
    markAccountBlocked();
    // The session stays so the blocked notice can offer sign-out, but cached user data should be dropped.
    notifyAuthCleared();
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

