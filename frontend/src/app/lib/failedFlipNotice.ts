import type { Card, GameEvent, GameStateView } from "../api/game";

/** How long a failed blind-flip reveal stays on screen: long enough to read the revealed card. */
export const FAILED_FLIP_NOTICE_MS = 2000;

/** A failed flip is shown only when its event is at most this old (server clock, compared with the client clock). */
export const FAILED_FLIP_RECENT_MS = 15000;

export type FailedFlip = { seq: number; card: Card };

export type FailedFlipStep = {
  /** Newest seq handled so far. The next state only looks for FAILED_FLIP events above it. */
  seq: number;
  /** The flip to show for this state, or null. */
  flip: FailedFlip | null;
};

/**
 * Decides the failed-flip popup for one state, from its FAILED_FLIP events only (every flipper: the viewer, other humans
 * and bots). `seenSeq` is the newest seq already handled, or null for the first state of a session: that state only sets
 * the baseline, so a load, refresh or reconnect never replays old flips. Each flip above `seenSeq` is handled once. The
 * newest one is shown if a human made it, it has exactly one card, is recent and `showable` (see revealsShown); older
 * ones are skipped. Bot flips still count as handled, so they are never shown later.
 */
export function nextFailedFlipStep(state: GameStateView, seenSeq: number | null, now: number, showable: boolean): FailedFlipStep {
  const events: GameEvent[] = state.events ?? [];
  if (seenSeq === null) {
    return { seq: events.reduce((max, event) => Math.max(max, event.seq), 0), flip: null };
  }
  let newest: GameEvent | null = null;
  for (const event of events) {
    if (event.type === "FAILED_FLIP" && event.seq > seenSeq && (!newest || event.seq > newest.seq)) newest = event;
  }
  if (!newest) return { seq: seenSeq, flip: null };
  // Bot flips only reach the activity log: the popup is for human flips (own and other humans').
  const flipper = state.players.find((player) => player.playerId === newest.playerId);
  const human = !!flipper && !flipper.isBot;
  const shown = showable && human && newest.cards.length === 1 && now - newest.ts <= FAILED_FLIP_RECENT_MS;
  return { seq: newest.seq, flip: shown ? { seq: newest.seq, card: newest.cards[0] } : null };
}
