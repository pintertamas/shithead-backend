import { Navigate, Route, Routes } from "react-router-dom";
import MenuLayout from "./components/MenuLayout";
import LazyRoute, { routeScreen } from "./components/LazyRoute";
import Login from "./screens/Login";
import Lobby from "./screens/Lobby";
import AccountBlockedGate from "./components/AccountBlockedGate";
import AuthCallback from "./auth/authCallback";
import { useAuth } from "./auth/useAuth";
import AppDataProvider from "./data/AppDataProvider";
import { installChunkReloadHandler } from "./lib/chunkReload";

// Everything except the lobby and sign-in screens is split into its own chunk and loaded on first visit (or on intent).
const Room = routeScreen("room");
const GameTable = routeScreen("game");
const Leaderboard = routeScreen("leaderboard");
const Profile = routeScreen("profile");
const GameConfig = routeScreen("config");
const Games = routeScreen("games");
const Admin = routeScreen("admin");

installChunkReloadHandler();

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
          <Route path="/profile" element={<LazyRoute><Profile /></LazyRoute>} />
          <Route path="/config" element={<LazyRoute><GameConfig /></LazyRoute>} />
          <Route path="/leaderboard" element={<LazyRoute><Leaderboard /></LazyRoute>} />
          <Route path="/leaderboard/:sessionId" element={<LazyRoute><Leaderboard /></LazyRoute>} />
          <Route path="/games" element={<LazyRoute><Games /></LazyRoute>} />
          <Route path="/admin" element={<LazyRoute><Admin /></LazyRoute>} />
        </Route>
        <Route
          path="/room/:sessionId"
          element={token ? <LazyRoute variant="bare"><Room /></LazyRoute> : <Navigate to="/login" />}
        />
        <Route
          path="/game/:sessionId"
          element={token ? <LazyRoute variant="bare"><GameTable /></LazyRoute> : <Navigate to="/login" />}
        />
      </Routes>
    </div>
    </AppDataProvider>
  );
}
