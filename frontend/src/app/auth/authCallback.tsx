import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { saveAuth } from "./useAuth";

export default function AuthCallback() {
  const navigate = useNavigate();

  useEffect(() => {
    const hash = window.location.hash.replace("#", "");
    const params = new URLSearchParams(hash);
    const idToken = params.get("id_token");
    const accessToken = params.get("access_token");
    const expiresIn = Number(params.get("expires_in") || "0");

    if (idToken && accessToken) {
      saveAuth({
        idToken,
        accessToken,
        expiresAt: Date.now() + expiresIn * 1000
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

