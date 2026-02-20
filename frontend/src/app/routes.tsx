import { Navigate, Route, Routes } from "react-router-dom";
import Login from "./screens/Login";
import Lobby from "./screens/Lobby";
import Room from "./screens/Room";
import GameTable from "./screens/GameTable";
import Leaderboard from "./screens/Leaderboard";
import AuthCallback from "./auth/authCallback";
import { useAuth } from "./auth/useAuth";

export default function AppRoutes() {
  const { token } = useAuth();

  return (
    <div className="app-shell">
      <Routes>
        <Route path="/" element={<Navigate to={token ? "/lobby" : "/login"} />} />
        <Route path="/login" element={<Login />} />
        <Route path="/auth/callback" element={<AuthCallback />} />
        <Route path="/logout" element={<Navigate to="/login" />} />
        <Route
          path="/lobby"
          element={token ? <Lobby /> : <Navigate to="/login" />}
        />
        <Route
          path="/room/:sessionId"
          element={token ? <Room /> : <Navigate to="/login" />}
        />
        <Route
          path="/game/:sessionId"
          element={token ? <GameTable /> : <Navigate to="/login" />}
        />
        <Route
          path="/leaderboard/:sessionId"
          element={token ? <Leaderboard /> : <Navigate to="/login" />}
        />
      </Routes>
    </div>
  );
}

