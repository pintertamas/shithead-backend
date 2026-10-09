import { Navigate, Route, Routes } from "react-router-dom";
import MenuLayout from "./components/MenuLayout";
import Login from "./screens/Login";
import Lobby from "./screens/Lobby";
import Room from "./screens/Room";
import GameTable from "./screens/GameTable";
import Leaderboard from "./screens/Leaderboard";
import Profile from "./screens/Profile";
import GameConfig from "./screens/GameConfig";
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
        <Route element={token ? <MenuLayout /> : <Navigate to="/login" />}>
          <Route path="/lobby" element={<Lobby />} />
          <Route path="/profile" element={<Profile />} />
          <Route path="/config" element={<GameConfig />} />
          <Route path="/leaderboard" element={<Leaderboard />} />
          <Route path="/leaderboard/:sessionId" element={<Leaderboard />} />
        </Route>
        <Route
          path="/room/:sessionId"
          element={token ? <Room /> : <Navigate to="/login" />}
        />
        <Route
          path="/game/:sessionId"
          element={token ? <GameTable /> : <Navigate to="/login" />}
        />
      </Routes>
    </div>
  );
}

