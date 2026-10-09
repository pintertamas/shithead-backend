// Set once any API call reports that the signed-in account has been blocked by an administrator.
export const ACCOUNT_BLOCKED_MESSAGE = "Your account has been blocked.";

let blocked = false;
const listeners = new Set<() => void>();

export function markAccountBlocked(): void {
  if (blocked) return;
  blocked = true;
  listeners.forEach((listener) => listener());
}

export function isAccountBlocked(): boolean {
  return blocked;
}

export function subscribeAccountBlocked(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
