import { useSyncExternalStore } from "react";
import { isAccountBlocked, subscribeAccountBlocked } from "../auth/accountBlocked";
import { useAuth } from "../auth/useAuth";
import "../styles/admin.css";

// Full-screen notice shown once any API call reports the account as blocked.
export default function AccountBlockedGate() {
  const blocked = useSyncExternalStore(subscribeAccountBlocked, isAccountBlocked, () => false);
  if (!blocked) return null;
  return <BlockedNotice />;
}

function BlockedNotice() {
  const { logout } = useAuth();
  return (
    <div
      className="account-blocked-overlay"
      role="alertdialog"
      aria-modal="true"
      aria-labelledby="account-blocked-title"
      aria-describedby="account-blocked-body"
    >
      <div className="account-blocked-card glass">
        <h1 id="account-blocked-title">Your account has been blocked.</h1>
        <p id="account-blocked-body">
          You can no longer play or browse games. If you think this is a mistake, contact a game administrator.
        </p>
        <button className="button" type="button" onClick={logout}>Sign out</button>
      </div>
    </div>
  );
}
