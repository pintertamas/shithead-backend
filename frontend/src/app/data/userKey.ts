// Decodes the Cognito subject (user id) from an ID token payload. Returns null for missing or malformed tokens.
export function userKeyFromToken(idToken: string): string | null {
  if (!idToken) return null;
  try {
    const base64 = idToken.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    const claims = JSON.parse(atob(base64)) as { sub?: unknown };
    return typeof claims.sub === "string" && claims.sub ? claims.sub : null;
  } catch {
    return null;
  }
}
