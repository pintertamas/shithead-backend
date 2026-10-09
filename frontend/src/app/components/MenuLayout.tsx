import { useEffect, useState } from "react";
import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { fetchProfile } from "../api/profile";
import { useAuth } from "../auth/useAuth";
import Icon from "./Icon";
import "../styles/nav-bottom.css";

export default function MenuLayout() {
  const { token, logout } = useAuth();
  const navigate = useNavigate();
  const [canAdmin, setCanAdmin] = useState(false);

  useEffect(() => {
    let active = true;
    fetchProfile(token)
      .then((profile) => { if (active) setCanAdmin(profile.canClearGames); })
      .catch(() => { if (active) setCanAdmin(false); });
    return () => { active = false; };
  }, [token]);

  return (
    <div className="page fade-in lobby-page">
      <aside className="lobby-sidebar" aria-label="Main navigation">
        <div className="lobby-brand" aria-label="Shithead card king logo">
          <svg viewBox="0 0 64 64" role="img" aria-label="A king with a poop crown on a playing card">
            <rect x="4" y="3" width="56" height="58" rx="10" fill="#183527" stroke="#e7d8a4" strokeWidth="1.5" />
            <rect x="8" y="7" width="48" height="50" rx="7" fill="none" stroke="#6f8b68" strokeWidth=".8" />
            <text x="12" y="19" fill="#f4e8c2" fontSize="10" fontWeight="700" fontFamily="serif">K</text>
            <path d="M11 21h5" stroke="#d7c58b" strokeWidth="1" />
            <path d="M16 24 13 27l3 3 3-3z" fill="#d7c58b" />
            <path d="M21 35c0-8 5-13 11-13s11 5 11 13v8H21z" fill="#d8c89d" stroke="#f2e5bd" strokeWidth="1" />
            <path d="M21 32c1-8 5-13 11-13s10 5 11 13l-4-4-3 3-4-4-4 4-3-3z" fill="#6a3f2e" stroke="#c39b67" strokeWidth="1" />
            <path d="M26 34h2m8 0h2" stroke="#243c2f" strokeWidth="1.6" strokeLinecap="round" />
            <path d="M27 40c2-3 8-3 10 0-1 3-3 4-5 4s-4-1-5-4z" fill="#4a3027" />
            <path d="M19 45h26l7 8H12z" fill="#31543d" stroke="#e7d8a4" strokeWidth="1" />
            <path d="m21 46 4 5m18-5-4 5M24 48h16" stroke="#d7c58b" strokeWidth="1" />
            <path d="m22 25-3-8 8 3 5-7 5 7 8-3-3 8z" fill="#d2aa4e" stroke="#f0d78c" strokeWidth=".9" strokeLinejoin="round" />
            <path d="M22 25h20v3H22z" fill="#b88d38" stroke="#f0d78c" strokeWidth=".8" />
            <path d="M28 18c-2-2-.5-5 2-5 0-3 4-4 5-1 3 0 4 3 2 5-2 3-7 3-9 1z" fill="#684330" stroke="#d0a46b" strokeWidth=".9" />
            <path d="M31 16c1-1 2-2 4-1" fill="none" stroke="#e4bf83" strokeWidth=".8" strokeLinecap="round" />
          </svg>
        </div>
        <nav className="lobby-nav">
          <NavLink className={({ isActive }) => `lobby-nav-item${isActive ? " active" : ""}`} to="/lobby" aria-label="Home" title="Home"><Icon name="home" className="lobby-nav-icon" /><span className="lobby-nav-label">Home</span></NavLink>
          <NavLink className={({ isActive }) => `lobby-nav-item${isActive ? " active" : ""}`} to="/config" aria-label="Game Config" title="Game Config"><Icon name="settings" className="lobby-nav-icon" /><span className="lobby-nav-label">Game Config</span></NavLink>
          <NavLink className={({ isActive }) => `lobby-nav-item${isActive ? " active" : ""}`} to="/games" aria-label="Browse games" title="Browse games"><Icon name="list" className="lobby-nav-icon" /><span className="lobby-nav-label">Browse games</span></NavLink>
          <NavLink className={({ isActive }) => `lobby-nav-item${isActive ? " active" : ""}`} to="/profile" aria-label="Profile" title="Profile"><Icon name="user" className="lobby-nav-icon" /><span className="lobby-nav-label">Profile</span></NavLink>
          {canAdmin && <NavLink className={({ isActive }) => `lobby-nav-item${isActive ? " active" : ""}`} to="/admin" aria-label="Admin" title="Admin"><Icon name="shield" className="lobby-nav-icon" /><span className="lobby-nav-label">Admin</span></NavLink>}
          <button className="lobby-nav-item lobby-nav-logout" onClick={logout} aria-label="Log out" title="Log out"><Icon name="logout" className="lobby-nav-icon" /><span className="lobby-nav-label">Log out</span></button>
        </nav>
      </aside>
      <main className="lobby-main"><Outlet /></main>
    </div>
  );
}
