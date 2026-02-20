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

