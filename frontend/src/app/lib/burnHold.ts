import type { Card, GameStateView } from "../api/game";
import { lastEventSeq } from "./tableAnimations";

/**
 * How long a burning play stays on the table before the pile is cleared. The server sends only the state after the
 * burn (the pile already empty), so the client first shows the pile with the burner on top, holds it for this long, and
 * only then applies the burned state (which starts the burn animation).
 */
export const BURN_HOLD_MS = 1000;

/**
 * True when the update `next` clears the discard pile by a burn (BURNED event) that was caused by exactly one play since
 * `previous` (one PLAYED event). Pickups, failed plays and anything else that empties the pile do not qualify, and
 * neither does an update whose events are missing.
 */
export function isBurnTransition(previous: GameStateView, next: GameStateView): boolean {
  if (!previous.started || previous.discardCount === 0 || next.discardCount !== 0) return false;
  const since = lastEventSeq(previous);
  const fresh = (next.events ?? []).filter((event) => event.seq > since);
  const plays = fresh.filter((event) => event.type === "PLAYED").length;
  return plays === 1 && fresh.some((event) => event.type === "BURNED");
}

/**
 * The state to show during the hold: the pile as it was before the burn with the burning play on top, everything else
 * from `next`. Null when the played cards cannot be read from the events, so the caller applies `next` at once.
 */
export function pileBeforeBurn(previous: GameStateView, next: GameStateView): GameStateView | null {
  if (previous.discardPile.length !== previous.discardCount) return null;
  const since = lastEventSeq(previous);
  const played: Card[] = (next.events ?? [])
    .filter((event) => event.seq > since && event.type === "PLAYED")
    .flatMap((event) => event.cards);
  if (played.length === 0) return null;
  const discardPile = [...previous.discardPile, ...played];
  return { ...next, discardPile, discardCount: discardPile.length };
}
