import { useAuth } from "../auth/useAuth";

const API_BASE = import.meta.env.VITE_API_BASE_URL;

export function apiFetch(path: string, token: string, options: RequestInit = {}) {
  return fetch(`${API_BASE}${path}`, {
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

  let detail = "";
  try {
    const body = await response.json() as { error?: string; message?: string };
    detail = body.error || body.message || "";
  } catch {
    // Some API errors have an empty body; the HTTP status is still useful feedback.
  }

  const statusMessage = response.status === 401 || response.status === 403
    ? "Check that you are signed in and have access."
    : response.status === 404
      ? "The requested game or resource was not found."
      : response.status === 409
        ? "That action is not available in the current game state."
        : "Please try again.";
  throw new Error(`${detail || `Couldn't ${action} (HTTP ${response.status}).`} ${statusMessage}`);
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

