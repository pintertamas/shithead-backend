import { apiFetch, throwForError } from "./client";

export type VoiceToken = {
  url: string;
  token: string;
  room: string;
};

/** Requests a LiveKit join token for the game. Only called when the player presses "Join voice". */
export async function fetchVoiceToken(token: string, sessionId: string): Promise<VoiceToken> {
  const response = await apiFetch(`/games/${encodeURIComponent(sessionId)}/voice-token`, token, { method: "POST" });
  await throwForError(response, "join voice chat");
  return response.json() as Promise<VoiceToken>;
}
