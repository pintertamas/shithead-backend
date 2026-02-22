import { useEffect } from "react";
import { clearAuth } from "../auth/useAuth";

const domain = import.meta.env.VITE_COGNITO_DOMAIN;
const clientId = import.meta.env.VITE_COGNITO_CLIENT_ID;
const redirectUri = import.meta.env.VITE_COGNITO_REDIRECT_URI;

export default function Login() {
  useEffect(() => {
    clearAuth();
  }, []);

  const loginParams = new URLSearchParams({
    response_type: "token",
    client_id: clientId,
    redirect_uri: redirectUri,
    scope: "openid profile email"
  });
  const loginUrl = `${domain}/login?${loginParams.toString()}`;

  return (
    <div className="page fade-in">
      <div className="glass card" style={{ maxWidth: 560, margin: "0 auto" }}>
        <span className="badge">Test Client</span>
        <h1 className="title">Shithead Lobby</h1>
        <p style={{ color: "var(--ink-dim)" }}>
          Sign in to create or join a session. The test client shows only the
          information you’re allowed to see.
        </p>
        <a className="button" href={loginUrl}>
          Sign In with Cognito
        </a>
      </div>
    </div>
  );
}

