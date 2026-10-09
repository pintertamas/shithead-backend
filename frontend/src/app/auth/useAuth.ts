import { useMemo, useSyncExternalStore } from "react";
import { clearAuth, getAuthSnapshot, subscribeAuth } from "./authStore";
import { getClaims } from "./claims";

const COGNITO_DOMAIN = import.meta.env.VITE_COGNITO_DOMAIN;
const COGNITO_CLIENT_ID = import.meta.env.VITE_COGNITO_CLIENT_ID;

// Re-exported so existing imports from this module keep working.
export { saveAuth, clearAuth, loadAuth, getFreshToken, onAuthCleared } from "./authStore";
export type { AuthState } from "./authStore";

export function useAuth() {
  const auth = useSyncExternalStore(subscribeAuth, getAuthSnapshot, getAuthSnapshot);
  const username = useMemo(() => (auth ? decodeUsername(auth.idToken) : ""), [auth]);

  // Also end the Cognito session; otherwise /oauth2/authorize silently reuses it and the same account signs in again.
  const logout = () => {
    clearAuth();
    try { localStorage.setItem("shithead_choose_account", "1"); } catch { /* storage unavailable */ }
    const logoutUri = import.meta.env.VITE_COGNITO_LOGOUT_URI;
    if (COGNITO_DOMAIN && COGNITO_CLIENT_ID && logoutUri) {
      window.location.assign(`${COGNITO_DOMAIN}/logout?${new URLSearchParams({ client_id: COGNITO_CLIENT_ID, logout_uri: logoutUri }).toString()}`);
    } else {
      window.location.assign(`${import.meta.env.BASE_URL}login`);
    }
  };

  return {
    token: auth?.idToken || "",
    accessToken: auth?.accessToken || "",
    username,
    logout
  };
}

function decodeUsername(idToken: string): string {
  const claims = getClaims(idToken);
  const email = claims?.["email"];
  const cognitoUsername = claims?.["cognito:username"];
  if (typeof email === "string" && email) return email;
  return typeof cognitoUsername === "string" ? cognitoUsername : "";
}
