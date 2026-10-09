import type { Card, CardSelection, GameStateView, PlayerState } from "../api/game";

/** The player's own card selection on the table. */
export type SelectionState = { selected: CardSelection[]; pickupSelected: boolean };

export const EMPTY_SELECTION: SelectionState = { selected: [], pickupSelected: false };

/** Two cards are the same card when suit, value and rule match. Missing cards never match. */
export function sameCard(a: Card | undefined, b: Card | undefined): boolean {
  return Boolean(a && b && a.suit === b.suit && a.value === b.value && a.rule === b.rule);
}

function sameCards(a: Card[] | undefined, b: Card[] | undefined): boolean {
  const left = a ?? [];
  const right = b ?? [];
  return left.length === right.length && left.every((card, index) => sameCard(card, right[index]));
}

function ownPlayer(state: GameStateView): PlayerState | undefined {
  return state.players.find((player) => player.isYou);
}

/**
 * True when a state change touches this player's own cards, readiness or turn. Such a change is the server
 * acknowledging a play, pickup or swap this player sent, so any pending selection is then cleared.
 * Other players' hands, elo, feed and chat do not count.
 */
export function affectsOwnCardsOrTurn(previous: GameStateView, next: GameStateView): boolean {
  const before = ownPlayer(previous);
  const now = ownPlayer(next);
  if (!before || !now) return true;
  return previous.currentPlayerId !== next.currentPlayerId
    || before.ready !== now.ready
    || before.handCount !== now.handCount
    || before.faceDownCount !== now.faceDownCount
    || !sameCards(before.hand, now.hand)
    || !sameCards(before.faceUp, now.faceUp);
}

/**
 * Whether one selected card survives a state change. Hand and face-up cards are kept while the card at the same
 * index is the same card. Face-down cards are hidden, so they are kept while the face-down count is unchanged
 * and the index still exists.
 */
function keepSelection(item: CardSelection, before: PlayerState, now: PlayerState): boolean {
  if (item.source === "hand") return sameCard(before.hand?.[item.index], now.hand?.[item.index]);
  if (item.source === "faceUp") return sameCard(before.faceUp[item.index], now.faceUp[item.index]);
  return before.faceDownCount === now.faceDownCount && item.index < now.faceDownCount;
}

/**
 * Decides what selection survives a state update.
 * - No previous state, or this player is missing: nothing is kept.
 * - A pending action sent by this player that is acknowledged by a change to their own cards or turn: everything is cleared.
 * - Otherwise each selected card is kept only if it is still at its index (see keepSelection). The discard-pile
 *   pickup is kept only while the pile has cards and it is still this player's turn.
 * Returns the same object when nothing changed so React skips the re-render.
 */
export function reconcileSelection(
  previous: GameStateView | null,
  next: GameStateView,
  current: SelectionState,
  actionPending: boolean
): SelectionState {
  if (!previous) return current;
  if (actionPending && affectsOwnCardsOrTurn(previous, next)) return EMPTY_SELECTION;
  const before = ownPlayer(previous);
  const now = ownPlayer(next);
  if (!before || !now) return EMPTY_SELECTION;
  const selected = current.selected.filter((item) => keepSelection(item, before, now));
  const pickupSelected = current.pickupSelected
    && next.discardCount > 0
    && next.currentPlayerId === now.playerId;
  if (pickupSelected === current.pickupSelected && selected.length === current.selected.length) return current;
  return { selected, pickupSelected };
}
