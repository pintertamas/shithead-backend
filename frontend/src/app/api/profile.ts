import { apiFetch, throwForError } from "./client";

export type UserProfile = {
  username: string;
  canClearGames: boolean;
};

export async function fetchProfile(token: string): Promise<UserProfile> {
  const response = await apiFetch("/profile", token, { method: "GET" });
  await throwForError(response, "load your profile");
  return response.json() as Promise<UserProfile>;
}

export async function updateProfile(token: string, username: string): Promise<UserProfile> {
  const response = await apiFetch("/profile", token, {
    method: "PUT",
    body: JSON.stringify({ username })
  });
  await throwForError(response, "save your profile");
  return response.json() as Promise<UserProfile>;
}

export async function clearAllGames(token: string): Promise<{
  deletedGames: number;
  closedConnections: number;
  failedConnections: number;
}> {
  const response = await apiFetch("/admin/doomsday", token, { method: "POST" });
  await throwForError(response, "clear active games");
  return response.json();
}
