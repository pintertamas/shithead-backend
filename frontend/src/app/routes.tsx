import { Navigate, Route, Routes } from "react-router-dom";
import MenuLayout from "./components/MenuLayout";
import Login from "./screens/Login";
import Lobby from "./screens/Lobby";
import Room from "./screens/Room";
import GameTable from "./screens/GameTable";
import Leaderboard from "./screens/Leaderboard";
import Profile from "./screens/Profile";
import GameConfig from "./screens/GameConfig";
import Games from "./screens/Games";
import Admin from "./screens/Admin";
import AccountBlockedGate from "./components/AccountBlockedGate";
import AuthCallback from "./auth/authCallback";
import { useAuth } from "./auth/useAuth";
import AppDataProvider from "./data/AppDataProvider";

export default function AppRoutes() {
  const { token } = useAuth();

  return (
    <AppDataProvider token={token}>
    <div className="app-shell">
      <AccountBlockedGate />
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
          <Route path="/games" element={<Games />} />
          <Route path="/admin" element={<Admin />} />
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
    </AppDataProvider>
  );
}

