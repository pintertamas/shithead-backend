import { useEffect, useState } from "react";

const STORAGE_KEY = "shithead_auth";
const COGNITO_DOMAIN = import.meta.env.VITE_COGNITO_DOMAIN;
const COGNITO_CLIENT_ID = import.meta.env.VITE_COGNITO_CLIENT_ID;

export type AuthState = {
  accessToken: string;
  idToken: string;
  expiresAt: number;
  refreshToken?: string;
};

export function saveAuth(state: AuthState) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
}

export function clearAuth() {
  localStorage.removeItem(STORAGE_KEY);
}

export function loadAuth(): AuthState | null {
  const raw = localStorage.getItem(STORAGE_KEY);
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

export function useAuth() {
  const [auth, setAuth] = useState<AuthState | null>(() => loadAuth());

  useEffect(() => {
    if (!auth) return;
    const refreshDelay = Math.max(0, auth.expiresAt - Date.now() - (auth.refreshToken ? 120_000 : 0));
    let retry: number | null = null;
    const refreshSession = async () => {
      if (!auth.refreshToken) {
        if (Date.now() >= auth.expiresAt) {
          clearAuth();
          setAuth(null);
        }
        return;
      }
      try {
        const response = await fetch(`${COGNITO_DOMAIN}/oauth2/token`, {
          method: "POST",
          headers: { "Content-Type": "application/x-www-form-urlencoded" },
          body: new URLSearchParams({
            grant_type: "refresh_token",
            client_id: COGNITO_CLIENT_ID,
            refresh_token: auth.refreshToken
          }).toString()
        });
        if (!response.ok) throw new Error("Session refresh was rejected.");
        const tokens = await response.json() as { id_token?: string; access_token?: string; expires_in?: number; refresh_token?: string };
        if (!tokens.id_token || !tokens.access_token) throw new Error("Session refresh returned incomplete tokens.");
        const refreshed: AuthState = {
          idToken: tokens.id_token,
          accessToken: tokens.access_token,
          expiresAt: Date.now() + (tokens.expires_in || 3600) * 1000,
          refreshToken: tokens.refresh_token || auth.refreshToken
        };
        saveAuth(refreshed);
        setAuth(refreshed);
      } catch {
        if (Date.now() >= auth.expiresAt) {
          clearAuth();
          setAuth(null);
        } else {
          retry = window.setTimeout(refreshSession, 30_000);
        }
      }
    };
    const timeout = window.setTimeout(refreshSession, refreshDelay);
    return () => {
      window.clearTimeout(timeout);
      if (retry !== null) window.clearTimeout(retry);
    };
  }, [auth]);

  // Also end the Cognito session; otherwise /oauth2/authorize silently reuses it and the same account signs in again.
  const logout = () => {
    clearAuth();
    const logoutUri = import.meta.env.VITE_COGNITO_LOGOUT_URI;
    if (COGNITO_DOMAIN && COGNITO_CLIENT_ID && logoutUri) {
      window.location.assign(`${COGNITO_DOMAIN}/logout?${new URLSearchParams({ client_id: COGNITO_CLIENT_ID, logout_uri: logoutUri }).toString()}`);
    } else {
      window.location.assign(`${import.meta.env.BASE_URL}login`);
    }
  };

  const token = auth?.idToken || "";
  const username = auth?.idToken ? decodeUsername(auth.idToken) : "";

  return {
    token,
    accessToken: auth?.accessToken || "",
    username,
    logout
  };
}

function decodeUsername(idToken: string): string {
  try {
    const payload = idToken.split(".")[1];
    const decoded = JSON.parse(atob(payload));
    return decoded["email"] || decoded["cognito:username"] || "";
  } catch {
    return "";
  }
}
