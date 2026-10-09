import { FormEvent, useEffect, useState } from "react";
import ErrorAlert from "../components/ErrorAlert";
import { clearAllGames, fetchProfile, updateProfile, UserProfile } from "../api/profile";
import { useAuth } from "../auth/useAuth";

export default function Profile() {
  const { token } = useAuth();
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [username, setUsername] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [showDoomsdayConfirmation, setShowDoomsdayConfirmation] = useState(false);
  const [confirmationText, setConfirmationText] = useState("");
  const [clearing, setClearing] = useState(false);

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

  const handleClearGames = async () => {
    if (confirmationText !== "DELETE") return;
    setError(null);
    setNotice(null);
    setClearing(true);
    try {
      const result = await clearAllGames(token);
      setShowDoomsdayConfirmation(false);
      setConfirmationText("");
      setNotice(result.failedConnections === 0
        ? `Removed ${result.deletedGames} games and closed ${result.closedConnections} live connections. Your profiles and ratings are unchanged.`
        : `Removed ${result.deletedGames} games. Closed ${result.closedConnections} connections; ${result.failedConnections} could not be closed. Please try again.`);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Couldn't clear the active games.");
    } finally {
      setClearing(false);
    }
  };

  return (
    <div className="menu-content fade-in">
      <ErrorAlert message={error} onDismiss={() => setError(null)} />
      {notice && <div className="success-alert" role="status">{notice}</div>}
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

        {profile?.canClearGames && (
          <div className="glass card">
            <h3 className="title">Game Maintenance</h3>
            <p style={{ color: "var(--ink-dim)" }}>
              Clear all active game sessions and disconnect their players. Player profiles and ratings stay saved.
            </p>
            <button className="button danger" type="button" onClick={() => setShowDoomsdayConfirmation(true)}>
              Clear All Active Games
            </button>
          </div>
        )}
      </div>

      {showDoomsdayConfirmation && (
        <div className="modal-backdrop" role="presentation">
          <div className="glass card" role="dialog" aria-modal="true" aria-labelledby="doomsday-title">
            <h3 className="title" id="doomsday-title">Clear every active game?</h3>
            <p style={{ color: "var(--ink-dim)" }}>
              This permanently deletes every active game and closes every live game connection. User profiles and ratings are kept. Type DELETE to confirm.
            </p>
            <input
              className="input"
              aria-label="Type DELETE to confirm"
              value={confirmationText}
              onChange={(event) => setConfirmationText(event.target.value)}
              disabled={clearing}
            />
            <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
              <button className="button danger" type="button" onClick={handleClearGames} disabled={confirmationText !== "DELETE" || clearing}>
                {clearing ? "Clearing..." : "Confirm and Clear Games"}
              </button>
              <button className="button secondary" type="button" onClick={() => setShowDoomsdayConfirmation(false)} disabled={clearing}>
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
