// Read-only decoding of Cognito ID token claims. The signature is not verified here; the backend does that.

const ADMIN_GROUP = "game-admin";

export type IdTokenClaims = Record<string, unknown>;

function decodeBase64Url(segment: string): string {
  const base64 = segment.replace(/-/g, "+").replace(/_/g, "/");
  const padded = base64 + "=".repeat((4 - (base64.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

/** Returns the decoded payload of a JWT, or null when the token is missing or malformed. */
export function getClaims(idToken: string): IdTokenClaims | null {
  try {
    const payload = idToken.split(".")[1];
    if (!payload) return null;
    const parsed: unknown = JSON.parse(decodeBase64Url(payload));
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? (parsed as IdTokenClaims) : null;
  } catch {
    return null;
  }
}

/** Cognito subject (stable user id), or "" when unavailable. */
export function getUserId(idToken: string): string {
  const sub = getClaims(idToken)?.sub;
  return typeof sub === "string" ? sub : "";
}

/** True when cognito:groups contains game-admin. Accepts an array or a string, like the backend does. */
export function isAdmin(idToken: string): boolean {
  const groups = getClaims(idToken)?.["cognito:groups"];
  if (Array.isArray(groups)) return groups.includes(ADMIN_GROUP);
  if (typeof groups === "string") {
    return groups
      .replace(/[[\]]/g, "")
      .split(",")
      .map((group) => group.trim())
      .includes(ADMIN_GROUP);
  }
  return false;
}
