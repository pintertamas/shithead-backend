import { useEffect, useState } from "react";
import { clearAuth } from "../auth/useAuth";
import "../styles/login.css";

const domain = import.meta.env.VITE_COGNITO_DOMAIN;
const clientId = import.meta.env.VITE_COGNITO_CLIENT_ID;
const redirectUri = import.meta.env.VITE_COGNITO_REDIRECT_URI;
const PKCE_VERIFIER_KEY = "cognito_pkce_verifier";
// Sends the browser straight to Google instead of the hosted sign-in page.
const IDENTITY_PROVIDER = "Google";

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

function GoogleLogo() {
  return (
    <svg width="20" height="20" viewBox="0 0 48 48" aria-hidden="true" focusable="false">
      <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z" />
      <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z" />
      <path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z" />
      <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z" />
    </svg>
  );
}

function CardFace({ rank, suit, className }: { rank: string; suit: string; className?: string }) {
  const isRed = suit === "♥" || suit === "♦";
  const classes = ["login-card", isRed ? "is-red" : "", className ?? ""].filter(Boolean).join(" ");
  return (
    <span className={classes}>
      <span className="login-card-corner">
        {rank}
        <span>{suit}</span>
      </span>
      <span className="login-card-pip">{suit}</span>
      <span className="login-card-corner login-card-corner-end">
        {rank}
        <span>{suit}</span>
      </span>
    </span>
  );
}

export default function Login() {
  const [isRedirecting, setIsRedirecting] = useState(false);
  const [startError, setStartError] = useState<string | null>(null);

  useEffect(() => {
    clearAuth();
  }, []);

  const startLogin = async () => {
    if (isRedirecting) return;

    setIsRedirecting(true);
    setStartError(null);
    try {
      const verifier = createVerifier();
      localStorage.setItem(PKCE_VERIFIER_KEY, verifier);

      const challenge = await createChallenge(verifier);
      const loginParams = new URLSearchParams({
        response_type: "code",
        client_id: clientId,
        redirect_uri: redirectUri,
        scope: "openid profile email",
        identity_provider: IDENTITY_PROVIDER,
        code_challenge_method: "S256",
        code_challenge: challenge
      });

      window.location.assign(`${domain}/oauth2/authorize?${loginParams.toString()}`);
    } catch (error) {
      console.error("Failed to start login", error);
      setStartError("Could not start sign-in. Make sure cookies and local storage are enabled, then try again.");
      setIsRedirecting(false);
    }
  };

  return (
    <main className="login-screen fade-in">
      <div className="login-decor" aria-hidden="true">
        <CardFace className="login-deco login-deco-a" rank="A" suit={"♠"} />
        <CardFace className="login-deco login-deco-b" rank="10" suit={"♦"} />
        <CardFace className="login-deco login-deco-c" rank="Q" suit={"♣"} />
        <span className="login-card login-card-back login-deco login-deco-d" />
      </div>

      <section className="login-panel glass" aria-labelledby="login-title">
        <div className="login-hand" aria-hidden="true">
          <span className="login-card login-card-back login-hand-left" />
          <CardFace className="login-hand-center" rank="K" suit={"♥"} />
          <CardFace className="login-hand-right" rank="A" suit={"♠"} />
        </div>

        <p className="login-kicker">Card game</p>
        <h1 id="login-title" className="login-title">Shithead</h1>
        <span className="login-rule" aria-hidden="true" />
        <p className="login-lede">
          Sign in to start a table or join your friends, and climb the leaderboard.
        </p>

        <button
          className="login-google-button"
          type="button"
          onClick={startLogin}
          disabled={isRedirecting}
          aria-busy={isRedirecting}
        >
          <span className="login-google-icon">
            <GoogleLogo />
          </span>
          <span>{isRedirecting ? "Redirecting to Google..." : "Log in with Google"}</span>
        </button>

        {startError && (
          <p className="login-error" role="alert">
            {startError}
          </p>
        )}

        <p className="login-note">Google opens so you can pick the account to play with.</p>
      </section>
    </main>
  );
}
