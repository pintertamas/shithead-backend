import { FormEvent, useEffect, useMemo, useState } from "react";
import ErrorAlert, { SuccessAlert } from "../components/ErrorAlert";
import DeleteAccountDialog from "../components/DeleteAccountDialog";
import { useAuth } from "../auth/useAuth";
import { getClaims } from "../auth/claims";
import { markAccountDeleted } from "../auth/accountDeleted";
import { deleteAccount } from "../api/profile";
import { useProfileQuery, useUpdateProfileMutation } from "../data/queries";
import { clearAppCache } from "../data/queryClient";

export default function Profile() {
  const { token, logout } = useAuth();
  const { data: profile = null, error: profileError } = useProfileQuery(token);
  const updateMutation = useUpdateProfileMutation(token);
  // Null until the user edits the field, so a background refresh never overwrites what they are typing.
  const [draft, setDraft] = useState<string | null>(null);
  const username = draft ?? profile?.username ?? "";
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const saving = updateMutation.isPending;
  const email = useMemo(() => {
    const value = getClaims(token)?.email;
    return typeof value === "string" && value.trim() ? value : null;
  }, [token]);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [deleting, setDeleting] = useState(false);

  const confirmDelete = async () => {
    setError(null);
    setDeleting(true);
    try {
      await deleteAccount(token);
    } catch (cause) {
      setDeleting(false);
      setError(cause instanceof Error ? cause.message : "Couldn't delete your account.");
      return;
    }
    // Local data and session go first; logout() then ends the Cognito session and returns to /login.
    markAccountDeleted();
    clearAppCache();
    logout();
  };

  useEffect(() => {
    if (profileError) setError(profileError instanceof Error ? profileError.message : "Couldn't load your profile.");
  }, [profileError]);

  const saveProfile = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);
    setNotice(null);
    try {
      await updateMutation.mutateAsync(username.trim());
      setDraft(null);
      setNotice("Your nickname has been saved.");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Couldn't save your profile.");
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
        <section className="glass card">
          <h3 className="title">Email</h3>
          <p className={email ? "profile-email" : "profile-email profile-email-missing"}>
            {email ?? "Not available"}
          </p>
        </section>

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
            onChange={(event) => setDraft(event.target.value)}
            disabled={!profile || saving}
            required
          />
          <div style={{ height: 12 }} />
          <button className="button" type="submit" disabled={!profile || saving || username.trim().length < 2}>
            {saving ? "Saving..." : "Save Nickname"}
          </button>
        </form>

        <section className="glass card danger-zone">
          <h3 className="title">Delete account</h3>
          <p className="danger-zone-text">
            Permanently deletes your profile, Elo rating and sign-in account. This cannot be undone.
          </p>
          <button className="button danger" type="button" onClick={() => setDeleteOpen(true)} disabled={deleting}>
            Delete account
          </button>
        </section>
      </div>

      {deleteOpen && (
        <DeleteAccountDialog
          busy={deleting}
          onConfirm={confirmDelete}
          onCancel={() => setDeleteOpen(false)}
        />
      )}
    </div>
  );
}
