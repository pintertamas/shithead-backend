import { apiFetch, throwForError } from "./client";

export type VoiceToken = {
  url: string;
  token: string;
  room: string;
};

/** Requests a LiveKit join token for a game's room (`sessionId` is the room's game id, e.g. a rematch game). */
export async function fetchVoiceToken(token: string, sessionId: string): Promise<VoiceToken> {
  const response = await apiFetch(`/games/${encodeURIComponent(sessionId)}/voice-token`, token, { method: "POST" });
  await throwForError(response, "join voice chat");
  return response.json() as Promise<VoiceToken>;
}
