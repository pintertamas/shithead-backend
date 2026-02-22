import { useEffect } from "react";
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
    console.error("Missing PKCE verifier in session storage");
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
    return null;
  }

  const tokens = (await response.json()) as TokenResponse;
  const idToken = tokens.id_token || "";
  const accessToken = tokens.access_token || "";

  if (!idToken || !accessToken) {
    console.error("Token response missing required tokens", tokens);
    return null;
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

  useEffect(() => {
    let cancelled = false;

    const completeLogin = async () => {
      const hashParams = new URLSearchParams(window.location.hash.replace(/^#/, ""));
      const queryParams = new URLSearchParams(window.location.search);

      const error = queryParams.get("error");
      if (error) {
        console.error("Cognito authorize error", error, queryParams.get("error_description"));
        if (!cancelled) navigate("/login", { replace: true });
        return;
      }

      const idToken = hashParams.get("id_token") || queryParams.get("id_token");
      const accessToken = hashParams.get("access_token") || queryParams.get("access_token");
      const expiresRaw = hashParams.get("expires_in") || queryParams.get("expires_in");
      const expiresIn = Number(expiresRaw || "3600");
      const ttlSeconds = resolveTtlSeconds(expiresIn);

      if (idToken && accessToken) {
        saveAuth({
          idToken,
          accessToken,
          expiresAt: Date.now() + ttlSeconds * 1000
        });
        if (!cancelled) navigate("/lobby", { replace: true });
        return;
      }

      const code = queryParams.get("code");
      if (code) {
        try {
          const exchanged = await exchangeCodeForTokens(code);
          if (exchanged) {
            saveAuth({
              idToken: exchanged.idToken,
              accessToken: exchanged.accessToken,
              expiresAt: Date.now() + exchanged.expiresIn * 1000
            });
            if (!cancelled) navigate("/lobby", { replace: true });
            return;
          }
        } catch (err) {
          console.error("Code exchange threw an error", err);
        }
      }

      if (!cancelled) navigate("/login", { replace: true });
    };

    completeLogin();

    return () => {
      cancelled = true;
    };
  }, [navigate]);

  return (
    <div className="page">
      <div className="glass card">Signing you in...</div>
    </div>
  );
}
