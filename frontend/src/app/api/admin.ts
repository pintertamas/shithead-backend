import { apiFetch, throwForError } from "./client";

export type AdminUser = {
  userId: string;
  username: string | null;
  eloScore: number;
  blocked: boolean;
  /** Missing until the backend sends it; a missing value is shown the same as null (no email on record). */
  email?: string | null;
};

export async function fetchAdminUsers(token: string): Promise<AdminUser[]> {
  const response = await apiFetch("/admin/users", token, { method: "GET" });
  await throwForError(response, "load users");
  return response.json() as Promise<AdminUser[]>;
}

export async function setUserBlocked(token: string, userId: string, blocked: boolean): Promise<void> {
  const action = blocked ? "block" : "unblock";
  const response = await apiFetch(`/admin/users/${encodeURIComponent(userId)}/${action}`, token, { method: "POST" });
  await throwForError(response, `${action} this user`);
}
