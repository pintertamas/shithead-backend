import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { saveAuth } from "./useAuth";

export default function AuthCallback() {
  const navigate = useNavigate();

  useEffect(() => {
    const hashParams = new URLSearchParams(window.location.hash.replace(/^#/, ""));
    const queryParams = new URLSearchParams(window.location.search);

    const idToken = hashParams.get("id_token") || queryParams.get("id_token");
    const accessToken = hashParams.get("access_token") || queryParams.get("access_token");

    const expiresRaw = hashParams.get("expires_in") || queryParams.get("expires_in");
    const expiresIn = Number(expiresRaw || "3600");
    const ttlSeconds = Number.isFinite(expiresIn) && expiresIn > 0 ? expiresIn : 3600;

    if (idToken && accessToken) {
      saveAuth({
        idToken,
        accessToken,
        expiresAt: Date.now() + ttlSeconds * 1000
      });
      navigate("/lobby", { replace: true });
      return;
    }

    navigate("/login", { replace: true });
  }, [navigate]);

  return (
    <div className="page">
      <div className="glass card">Signing you in…</div>
    </div>
  );
}

