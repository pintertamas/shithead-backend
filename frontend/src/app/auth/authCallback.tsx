import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { saveAuth, loadAuth } from "./useAuth";
import { requestLoginSound } from "../lib/fartSound";
import "../styles/login.css";

const domain = import.meta.env.VITE_COGNITO_DOMAIN;
const clientId = import.meta.env.VITE_COGNITO_CLIENT_ID;
const redirectUri = import.meta.env.VITE_COGNITO_REDIRECT_URI;
const PKCE_VERIFIER_KEY = "cognito_pkce_verifier";

type TokenResponse = {
  access_token?: string;
  id_token?: string;
  expires_in?: number;
  refresh_token?: string;
};

function resolveTtlSeconds(expiresIn?: number): number {
  return Number.isFinite(expiresIn) && (expiresIn || 0) > 0 ? Number(expiresIn) : 3600;
}

async function exchangeCodeForTokens(code: string): Promise<{ idToken: string; accessToken: string; expiresIn: number; refreshToken?: string } | null> {
  const verifier = localStorage.getItem(PKCE_VERIFIER_KEY);
  console.log("[auth] verifier present:", !!verifier);
  if (!verifier) {
    console.error("[auth] Missing PKCE verifier in localStorage");
    return null;
  }

  const tokenParams = new URLSearchParams({
    grant_type: "authorization_code",
    client_id: clientId,
    code,
    redirect_uri: redirectUri,
    code_verifier: verifier
  });

  console.log("[auth] POSTing to token endpoint, redirect_uri:", redirectUri);
  const response = await fetch(`${domain}/oauth2/token`, {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded"
    },
    body: tokenParams.toString()
  });

  console.log("[auth] token response status:", response.status, response.ok);
  if (!response.ok) {
    const errorBody = await response.text();
    console.error("[auth] token exchange failed", response.status, errorBody);
    throw new Error(`Token exchange failed (HTTP ${response.status}): ${errorBody}`);
  }

  const tokens = (await response.json()) as TokenResponse;
  console.log("[auth] token response keys:", Object.keys(tokens));
  const idToken = tokens.id_token || "";
  const accessToken = tokens.access_token || "";

  if (!idToken || !accessToken) {
    console.error("[auth] response missing tokens", tokens);
    throw new Error("Auth server returned an incomplete response (missing id_token or access_token).");
  }

  localStorage.removeItem(PKCE_VERIFIER_KEY);
  return {
    idToken,
    accessToken,
    expiresIn: resolveTtlSeconds(tokens.expires_in),
    refreshToken: tokens.refresh_token
  };
}

export default function AuthCallback() {
  const navigate = useNavigate();
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    const completeLogin = async () => {
      // Guard: if auth is already valid (e.g. effect ran twice), just go to lobby.
      if (loadAuth()) {
        if (!cancelled) navigate("/lobby", { replace: true });
        return;
      }

      console.log("[auth] completeLogin started, url:", window.location.href);
      const hashParams = new URLSearchParams(window.location.hash.replace(/^#/, ""));
      const queryParams = new URLSearchParams(window.location.search);

      const error = queryParams.get("error");
      if (error) {
        const desc = queryParams.get("error_description") || error;
        console.error("[auth] Cognito authorize error", error, desc);
        if (!cancelled) setErrorMsg(`Google sign-in was not completed: ${desc}`);
        return;
      }

      const idToken = hashParams.get("id_token") || queryParams.get("id_token");
      const accessToken = hashParams.get("access_token") || queryParams.get("access_token");
      const expiresRaw = hashParams.get("expires_in") || queryParams.get("expires_in");
      const expiresIn = Number(expiresRaw || "3600");
      const ttlSeconds = resolveTtlSeconds(expiresIn);

      if (idToken && accessToken) {
        console.log("[auth] implicit flow tokens found");
        try {
          saveAuth({ idToken, accessToken, expiresAt: Date.now() + ttlSeconds * 1000 });
        } catch (saveErr) {
          console.error("[auth] failed to save implicit flow auth", saveErr);
          if (!cancelled) setErrorMsg("Could not save your session (storage blocked?). Try allowing cookies/storage for this site in your browser settings.");
          return;
        }
        if (!cancelled) navigate("/lobby", { replace: true });
        return;
      }

      const code = queryParams.get("code");
      console.log("[auth] authorization code present:", !!code);
      if (!code) {
        console.error("[auth] no code in URL, search was:", window.location.search);
        if (!cancelled) setErrorMsg("No authorization code was returned. Please try signing in again.");
        return;
      }

      if (!localStorage.getItem(PKCE_VERIFIER_KEY)) {
        if (!cancelled) setErrorMsg("Your sign-in session expired. Please try signing in again.");
        return;
      }

      let exchanged: { idToken: string; accessToken: string; expiresIn: number; refreshToken?: string } | null = null;
      try {
        exchanged = await exchangeCodeForTokens(code);
      } catch (exchangeErr) {
        console.error("[auth] token exchange threw", exchangeErr);
        if (!cancelled) setErrorMsg("We could not complete your sign-in. Please try again.");
        return;
      }

      if (!exchanged) {
        if (!cancelled) setErrorMsg("Token exchange returned no result. Please try signing in again.");
        return;
      }

      console.log("[auth] exchange succeeded, saving auth...");
      try {
        saveAuth({
          idToken: exchanged.idToken,
          accessToken: exchanged.accessToken,
          expiresAt: Date.now() + exchanged.expiresIn * 1000,
          refreshToken: exchanged.refreshToken
        });
      } catch (saveErr) {
        console.error("[auth] failed to save tokens", saveErr);
        if (!cancelled) setErrorMsg("Could not save your session (storage blocked?). Try allowing cookies/storage for this site in your browser settings.");
        return;
      }

      const rawStored = localStorage.getItem("shithead_auth");
      const authCheck = loadAuth();
      console.log("[auth] localStorage raw present:", !!rawStored);
      console.log("[auth] loadAuth() result:", authCheck ? "valid" : "null");

      if (!authCheck) {
        const raw = rawStored ? JSON.parse(rawStored) : null;
        console.error("[auth] loadAuth returned null despite save. Raw:", raw);
        if (!cancelled) setErrorMsg("Your session could not be verified. Please try signing in again.");
        return;
      }

      console.log("[auth] navigating to /lobby");
      requestLoginSound();
      window.location.replace(import.meta.env.BASE_URL + "lobby");
    };

    completeLogin();

    return () => {
      cancelled = true;
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (errorMsg) {
    return (
      <main className="auth-screen fade-in">
        <section className="auth-panel glass" role="alert" aria-labelledby="auth-error-title">
          <p className="login-kicker">Shithead</p>
          <span className="auth-error-mark" aria-hidden="true">!</span>
          <h1 id="auth-error-title" className="auth-title">We could not sign you in</h1>
          <p className="auth-detail">{errorMsg}</p>
          <button className="button" type="button" onClick={() => navigate("/login", { replace: true })}>
            Back to login
          </button>
        </section>
      </main>
    );
  }

  return (
    <main className="auth-screen fade-in">
      <section className="auth-panel glass" role="status" aria-live="polite">
        <p className="login-kicker">Shithead</p>
        <span className="auth-spinner" aria-hidden="true" />
        <h1 className="auth-title">Signing you in...</h1>
      </section>
    </main>
  );
}
