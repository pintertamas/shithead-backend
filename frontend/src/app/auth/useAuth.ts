const STORAGE_KEY = "shithead_auth";

export type AuthState = {
  accessToken: string;
  idToken: string;
  expiresAt: number;
};

export function saveAuth(state: AuthState) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
}

export function clearAuth() {
  localStorage.removeItem(STORAGE_KEY);
}

function loadAuth(): AuthState | null {
  const raw = localStorage.getItem(STORAGE_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as AuthState;
    if (!parsed.accessToken || !parsed.idToken) return null;
    if (parsed.expiresAt && Date.now() > parsed.expiresAt) return null;
    return parsed;
  } catch {
    return null;
  }
}

export function useAuth() {
  // Read from localStorage each time so route changes see fresh auth.
  const auth = loadAuth();
  const token = auth?.idToken || "";
  const username = auth?.idToken ? decodeUsername(auth.idToken) : "";

  return {
    token,
    accessToken: auth?.accessToken || "",
    username,
    logout: clearAuth
  };
}

function decodeUsername(idToken: string): string {
  try {
    const payload = idToken.split(".")[1];
    const decoded = JSON.parse(atob(payload));
    return decoded["cognito:username"] || decoded["email"] || "";
  } catch {
    return "";
  }
}

