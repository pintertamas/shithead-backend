import { FormEvent, useEffect, useState } from "react";
import ErrorAlert, { SuccessAlert } from "../components/ErrorAlert";
import { fetchProfile, updateProfile, UserProfile } from "../api/profile";
import { useAuth } from "../auth/useAuth";

export default function Profile() {
  const { token } = useAuth();
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [username, setUsername] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    fetchProfile(token).then((result) => {
      setProfile(result);
      setUsername(result.username);
    }).catch((cause: unknown) => {
      setError(cause instanceof Error ? cause.message : "Couldn't load your profile.");
    });
  }, [token]);

  const saveProfile = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);
    setNotice(null);
    setSaving(true);
    try {
      const updated = await updateProfile(token, username.trim());
      setProfile(updated);
      setUsername(updated.username);
      setNotice("Your nickname has been saved.");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Couldn't save your profile.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="menu-content fade-in">
      <ErrorAlert message={error} onDismiss={() => setError(null)} />
      <SuccessAlert message={notice} onDismiss={() => setNotice(null)} />
      <div className="topbar">
        <div>
          <div className="badge">Account</div>
          <h2 className="title">Your Profile</h2>
        </div>
      </div>

      <div className="layout single">
        <form className="glass card" onSubmit={saveProfile}>
          <h3 className="title">Nickname</h3>
          <p style={{ color: "var(--ink-dim)" }}>
            This name appears to other players instead of your email address. Nicknames must be unique.
          </p>
          <input
            className="input"
            aria-label="Nickname"
            minLength={2}
            maxLength={24}
            title="Use 2–24 letters, numbers, spaces, hyphens, or underscores."
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            disabled={!profile || saving}
            required
          />
          <div style={{ height: 12 }} />
          <button className="button" type="submit" disabled={!profile || saving || username.trim().length < 2}>
            {saving ? "Saving..." : "Save Nickname"}
          </button>
        </form>
      </div>
    </div>
  );
}
