import { useEffect, useState } from "react";
import { clearAuth } from "../auth/useAuth";

const domain = import.meta.env.VITE_COGNITO_DOMAIN;
const clientId = import.meta.env.VITE_COGNITO_CLIENT_ID;
const redirectUri = import.meta.env.VITE_COGNITO_REDIRECT_URI;
const PKCE_VERIFIER_KEY = "cognito_pkce_verifier";

function toBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const value of bytes) {
    binary += String.fromCharCode(value);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function createVerifier(): string {
  const bytes = new Uint8Array(64);
  crypto.getRandomValues(bytes);
  return toBase64Url(bytes);
}

async function createChallenge(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
  return toBase64Url(new Uint8Array(digest));
}

export default function Login() {
  const [isRedirecting, setIsRedirecting] = useState(false);

  useEffect(() => {
    clearAuth();
  }, []);

  const startLogin = async () => {
    if (isRedirecting) return;

    setIsRedirecting(true);
    try {
      const verifier = createVerifier();
      sessionStorage.setItem(PKCE_VERIFIER_KEY, verifier);

      const challenge = await createChallenge(verifier);
      const loginParams = new URLSearchParams({
        response_type: "code",
        client_id: clientId,
        redirect_uri: redirectUri,
        scope: "openid profile email",
        code_challenge_method: "S256",
        code_challenge: challenge
      });

      window.location.assign(`${domain}/login?${loginParams.toString()}`);
    } catch (error) {
      console.error("Failed to start Cognito login", error);
      setIsRedirecting(false);
    }
  };

  return (
    <div className="page fade-in">
      <div className="glass card" style={{ maxWidth: 560, margin: "0 auto" }}>
        <span className="badge">Test Client</span>
        <h1 className="title">Shithead Lobby</h1>
        <p style={{ color: "var(--ink-dim)" }}>
          Sign in to create or join a session. The test client shows only the
          information you are allowed to see.
        </p>
        <button className="button" type="button" onClick={startLogin} disabled={isRedirecting}>
          {isRedirecting ? "Redirecting..." : "Sign In with Cognito"}
        </button>
      </div>
    </div>
  );
}
