// Set just before a deleted account is signed out, so /login can show a notice after the Cognito logout redirect.
// sessionStorage keeps it for this tab only and survives that redirect.
const STORAGE_KEY = "shithead_account_deleted";

export function markAccountDeleted(): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, "1");
  } catch {
    // Storage unavailable: the user still lands on /login, just without the notice.
  }
}

export function isAccountDeleted(): boolean {
  try {
    return sessionStorage.getItem(STORAGE_KEY) === "1";
  } catch {
    return false;
  }
}

export function clearAccountDeleted(): void {
  try {
    sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    // Storage unavailable: nothing to clear.
  }
}
