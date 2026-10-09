import { useEffect, useMemo, useState } from "react";
import { AdminUser } from "../api/admin";
import { clearAllGames } from "../api/profile";
import { useAuth } from "../auth/useAuth";
import ErrorAlert, { SuccessAlert } from "../components/ErrorAlert";
import { AdminRowsSkeleton } from "../components/Skeleton";
import { invalidateGames, useAdminUsersQuery, useSetUserBlockedMutation } from "../data/queries";
import "../styles/admin.css";

type PendingChange = { user: AdminUser; blocked: boolean };

export default function Admin() {
  const { token } = useAuth();
  const ownId = useMemo(() => subjectOf(token), [token]);
  const usersQuery = useAdminUsersQuery(token);
  const blockMutation = useSetUserBlockedMutation(token);
  const users: AdminUser[] = usersQuery.data ?? [];
  const loading = usersQuery.isPending;
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [pending, setPending] = useState<PendingChange | null>(null);
  const [busyUserId, setBusyUserId] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [showDoomsdayConfirmation, setShowDoomsdayConfirmation] = useState(false);
  const [confirmationText, setConfirmationText] = useState("");
  const [clearing, setClearing] = useState(false);

  useEffect(() => {
    if (usersQuery.isError) setError(messageOf(usersQuery.error, "Couldn't load users."));
  }, [usersQuery.isError, usersQuery.error]);

  useEffect(() => {
    if (!pending) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") setPending(null);
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [pending]);

  const visibleUsers = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return users;
    return users.filter((user) =>
      (user.username ?? "").toLowerCase().includes(needle) || user.userId.toLowerCase().includes(needle));
  }, [users, query]);

  const confirmChange = async () => {
    if (!pending) return;
    const { user, blocked } = pending;
    setPending(null);
    setBusyUserId(user.userId);
    setError(null);
    try {
      // The row updates optimistically and rolls back on error; the mutation refetches the list when it settles.
      await blockMutation.mutateAsync({ userId: user.userId, blocked });
    } catch (cause) {
      setError(messageOf(cause, blocked ? "Couldn't block this user." : "Couldn't unblock this user."));
    } finally {
      setBusyUserId(null);
    }
  };

  const handleClearGames = async () => {
    if (confirmationText !== "DELETE") return;
    setError(null);
    setNotice(null);
    setClearing(true);
    try {
      const result = await clearAllGames(token);
      void invalidateGames(token);
      setShowDoomsdayConfirmation(false);
      setConfirmationText("");
      setNotice(result.failedConnections === 0
        ? `Removed ${result.deletedGames} games and closed ${result.closedConnections} live connections. Your profiles and ratings are unchanged.`
        : `Removed ${result.deletedGames} games. Closed ${result.closedConnections} connections; ${result.failedConnections} could not be closed. Please try again.`);
    } catch (cause) {
      setError(messageOf(cause, "Couldn't clear the active games."));
    } finally {
      setClearing(false);
    }
  };

  return (
    <div className="admin-page fade-in">
      <ErrorAlert message={error} onDismiss={() => setError(null)} />
      <SuccessAlert message={notice} onDismiss={() => setNotice(null)} />
      <header className="lobby-welcome">
        <div>
          <h1>Admin</h1>
          <p>Review players and block accounts that break the rules.</p>
        </div>
      </header>

      <div className="admin-toolbar">
        <input
          className="input admin-search"
          type="search"
          placeholder="Search by nickname or user id"
          aria-label="Search users"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
        {!loading && <p className="admin-count">{visibleUsers.length} of {users.length} users</p>}
      </div>

      {loading ? (
        <AdminRowsSkeleton rows={5} />
      ) : visibleUsers.length === 0 ? (
        <p className="lobby-rankings-message">No users match your search.</p>
      ) : (
        <div className="admin-table-wrap glass">
          <table className="admin-table">
            <thead>
              <tr>
                <th scope="col">User</th>
                <th scope="col">ELO</th>
                <th scope="col">Status</th>
                <th scope="col"><span className="visually-hidden">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {visibleUsers.map((user) => {
                const isSelf = user.userId === ownId;
                const busy = busyUserId === user.userId;
                return (
                  <tr key={user.userId} className={user.blocked ? "admin-row blocked" : "admin-row"}>
                    <td data-label="User">
                      <span className="admin-username">{user.username || "(no nickname)"}</span>
                      <span className="admin-userid">{user.userId}</span>
                      {isSelf && <span className="admin-you">This is you</span>}
                    </td>
                    <td data-label="ELO" className="admin-elo">{Math.round(user.eloScore)}</td>
                    <td data-label="Status">
                      {user.blocked
                        ? <span className="badge admin-blocked-badge">Blocked</span>
                        : <span className="admin-active">Active</span>}
                    </td>
                    <td data-label="Action" className="admin-action-cell">
                      {user.blocked ? (
                        <button
                          className="button secondary admin-action"
                          type="button"
                          disabled={busy}
                          onClick={() => setPending({ user, blocked: false })}
                        >
                          {busy ? "Saving…" : "Unblock"}
                        </button>
                      ) : (
                        <button
                          className="button danger admin-action"
                          type="button"
                          disabled={busy || isSelf}
                          title={isSelf ? "You cannot block your own account" : undefined}
                          onClick={() => setPending({ user, blocked: true })}
                        >
                          {busy ? "Saving…" : "Block"}
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      <section className="glass card admin-maintenance" aria-labelledby="admin-maintenance-title">
        <h3 className="title" id="admin-maintenance-title">Game Maintenance</h3>
        <p className="admin-dialog-text">
          Clear all active game sessions and disconnect their players. Player profiles and ratings stay saved.
        </p>
        <button className="button danger" type="button" onClick={() => setShowDoomsdayConfirmation(true)}>
          Clear All Active Games
        </button>
      </section>

      {showDoomsdayConfirmation && (
        <div className="modal-backdrop" role="presentation">
          <div className="glass card admin-dialog" role="dialog" aria-modal="true" aria-labelledby="doomsday-title">
            <h3 className="title" id="doomsday-title">Clear every active game?</h3>
            <p className="admin-dialog-text">
              This permanently deletes every active game and closes every live game connection. User profiles and ratings are kept. Type DELETE to confirm.
            </p>
            <input
              className="input"
              aria-label="Type DELETE to confirm"
              value={confirmationText}
              onChange={(event) => setConfirmationText(event.target.value)}
              disabled={clearing}
            />
            <div className="admin-dialog-actions">
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

      {pending && (
        <div className="modal-backdrop" role="presentation">
          <div className="glass card admin-dialog" role="dialog" aria-modal="true" aria-labelledby="admin-confirm-title">
            <h3 className="title" id="admin-confirm-title">
              {pending.blocked ? "Block" : "Unblock"} {pending.user.username || "this user"}?
            </h3>
            <p className="admin-dialog-text">
              {pending.blocked
                ? "They will be signed out of live games and removed from lobbies they have not started. Every request they make will be refused until you unblock them."
                : "They will be able to play and join games again."}
            </p>
            <div className="admin-dialog-actions">
              <button
                className={pending.blocked ? "button danger" : "button"}
                type="button"
                onClick={confirmChange}
              >
                {pending.blocked ? "Block user" : "Unblock user"}
              </button>
              <button className="button secondary" type="button" onClick={() => setPending(null)}>
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// Cognito subject (user id) from the ID token payload, used to stop admins blocking themselves.
function subjectOf(token: string): string | null {
  try {
    const base64 = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    const claims = JSON.parse(atob(base64)) as { sub?: unknown };
    return typeof claims.sub === "string" ? claims.sub : null;
  } catch {
    return null;
  }
}

function messageOf(cause: unknown, fallback: string): string {
  return cause instanceof Error ? cause.message : fallback;
}
