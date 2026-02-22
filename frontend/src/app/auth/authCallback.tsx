import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { saveAuth } from "./useAuth";

const domain = import.meta.env.VITE_COGNITO_DOMAIN;
const clientId = import.meta.env.VITE_COGNITO_CLIENT_ID;
const redirectUri = import.meta.env.VITE_COGNITO_REDIRECT_URI;
const PKCE_VERIFIER_KEY = "cognito_pkce_verifier";

type TokenResponse = {
  access_token?: string;
  id_token?: string;
  expires_in?: number;
};

function resolveTtlSeconds(expiresIn?: number): number {
  return Number.isFinite(expiresIn) && (expiresIn || 0) > 0 ? Number(expiresIn) : 3600;
}

async function exchangeCodeForTokens(code: string): Promise<{ idToken: string; accessToken: string; expiresIn: number } | null> {
  const verifier = localStorage.getItem(PKCE_VERIFIER_KEY);
  if (!verifier) {
    console.error("Missing PKCE verifier in local storage");
    return null;
  }

  const tokenParams = new URLSearchParams({
    grant_type: "authorization_code",
    client_id: clientId,
    code,
    redirect_uri: redirectUri,
    code_verifier: verifier
  });

  const response = await fetch(`${domain}/oauth2/token`, {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded"
    },
    body: tokenParams.toString()
  });

  if (!response.ok) {
    const errorBody = await response.text();
    console.error("Cognito token exchange failed", response.status, errorBody);
    throw new Error(`Token exchange failed (HTTP ${response.status}): ${errorBody}`);
  }

  const tokens = (await response.json()) as TokenResponse;
  const idToken = tokens.id_token || "";
  const accessToken = tokens.access_token || "";

  if (!idToken || !accessToken) {
    console.error("Token response missing required tokens", tokens);
    throw new Error("Auth server returned an incomplete response (missing id_token or access_token).");
  }

  localStorage.removeItem(PKCE_VERIFIER_KEY);
  return {
    idToken,
    accessToken,
    expiresIn: resolveTtlSeconds(tokens.expires_in)
  };
}

export default function AuthCallback() {
  const navigate = useNavigate();
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    const completeLogin = async () => {
      const hashParams = new URLSearchParams(window.location.hash.replace(/^#/, ""));
      const queryParams = new URLSearchParams(window.location.search);

      const error = queryParams.get("error");
      if (error) {
        const desc = queryParams.get("error_description") || error;
        console.error("Cognito authorize error", error, desc);
        if (!cancelled) setErrorMsg(`Sign-in was rejected by the auth server: ${desc}`);
        return;
      }

      const idToken = hashParams.get("id_token") || queryParams.get("id_token");
      const accessToken = hashParams.get("access_token") || queryParams.get("access_token");
      const expiresRaw = hashParams.get("expires_in") || queryParams.get("expires_in");
      const expiresIn = Number(expiresRaw || "3600");
      const ttlSeconds = resolveTtlSeconds(expiresIn);

      if (idToken && accessToken) {
        try {
          saveAuth({ idToken, accessToken, expiresAt: Date.now() + ttlSeconds * 1000 });
        } catch (saveErr) {
          console.error("Failed to save auth from implicit flow", saveErr);
          if (!cancelled) setErrorMsg("Could not save your session (storage blocked?). Try allowing cookies/storage for this site in your browser settings.");
          return;
        }
        if (!cancelled) navigate("/lobby", { replace: true });
        return;
      }

      const code = queryParams.get("code");
      if (!code) {
        if (!cancelled) setErrorMsg("No authorization code was returned. Please try signing in again.");
        return;
      }

      // Check verifier before attempting exchange
      if (!localStorage.getItem(PKCE_VERIFIER_KEY)) {
        if (!cancelled) setErrorMsg("Login session expired (PKCE verifier missing). Please try signing in again.");
        return;
      }

      let exchanged: { idToken: string; accessToken: string; expiresIn: number } | null = null;
      try {
        exchanged = await exchangeCodeForTokens(code);
      } catch (exchangeErr) {
        console.error("Token exchange error", exchangeErr);
        if (!cancelled) {
          const msg = exchangeErr instanceof Error ? exchangeErr.message : String(exchangeErr);
          setErrorMsg(msg);
        }
        return;
      }

      if (!exchanged) {
        if (!cancelled) setErrorMsg("Token exchange returned no result. Please try signing in again.");
        return;
      }

      try {
        saveAuth({
          idToken: exchanged.idToken,
          accessToken: exchanged.accessToken,
          expiresAt: Date.now() + exchanged.expiresIn * 1000
        });
      } catch (saveErr) {
        console.error("Failed to save auth tokens", saveErr);
        if (!cancelled) setErrorMsg("Could not save your session (storage blocked?). Try allowing cookies/storage for this site in your browser settings.");
        return;
      }

      if (!cancelled) navigate("/lobby", { replace: true });
    };

    completeLogin();

    return () => {
      cancelled = true;
    };
  }, [navigate]);

  if (errorMsg) {
    return (
      <div className="page">
        <div className="glass card">
          <p style={{ color: "var(--ink-dim)", marginBottom: "1rem" }}>{errorMsg}</p>
          <button className="button" type="button" onClick={() => navigate("/login", { replace: true })}>
            Back to Login
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="page">
      <div className="glass card">Signing you in...</div>
    </div>
  );
}