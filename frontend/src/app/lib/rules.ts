import type { Card } from "../api/game";

/**
 * Pure rule checks that mirror the backend (rules/RuleEngine.java and the *RuleStrategy.java files).
 * The server stays the authority; these only tell the table whether the player has any legal move.
 */

/** Whether `card` may be put on `pile` (cards in play order, last = top). Mirrors RuleEngine.canPlay. */
export function canPlayOn(card: Card, pile: Card[]): boolean {
  if (pile.length === 0 || card.alwaysPlayable) return true;
  // TRANSPARENT cards on top are looked through; the first card beneath decides. If there is none, the pile is empty.
  let index = pile.length - 1;
  while (index >= 0 && pile[index].rule === "TRANSPARENT") index--;
  if (index < 0) return true;
  const top = pile[index];
  switch (top.rule) {
    case "JOKER":
    case "BURNER":
      return true;
    case "SMALLER":
      return card.value <= top.value;
    default:
      // DEFAULT and REVERSE: equal or higher.
      return card.value >= top.value;
  }
}

export type MoveInput = {
  /** False during the swap phase: no pick-up selection applies there. */
  setupComplete: boolean;
  finished: boolean;
  yourTurn: boolean;
  discardPile: Card[];
  hand: Card[];
  faceUp: Card[];
  deckCount: number;
  allowMixedHandAndFaceUpWhenDeckEmpty: boolean;
};

/**
 * The candidate cards for the forced pick-up check (see mustPickUp):
 * - The hand, when the hand has any cards. Face-up cards are not candidates then, even when mixing is allowed
 *   (allowMixedHandAndFaceUpWhenDeckEmpty): a face-up card cannot be played alone while the hand has cards, and a
 *   mixed play needs a playable hand card, so the hand cards decide.
 * - The face-up cards, when the hand is empty.
 * Face-down cards are never candidates. With only face-down cards there are no candidates, so nothing is forced:
 * a blind flip is always allowed.
 */
export function playableSourceCards(input: MoveInput): Card[] {
  return input.hand.length > 0 ? input.hand : input.faceUp;
}

/**
 * True when it is this player's turn, the discard pile has cards and none of the candidate cards from
 * playableSourceCards can be played on it. In that case the table selects the pick-up. With only face-down cards
 * there are no candidates, so the result is false: a blind flip is always allowed.
 */
export function mustPickUp(input: MoveInput): boolean {
  if (!input.setupComplete || input.finished || !input.yourTurn) return false;
  if (input.discardPile.length === 0) return false;
  const candidates = playableSourceCards(input);
  if (candidates.length === 0) return false;
  return candidates.every((card) => !canPlayOn(card, input.discardPile));
}

/**
 * A small key for the situation that decides the forced pick-up: whose turn, the discard pile top and size, and the
 * contents of hand and face-up cards. The table re-evaluates only when this key changes.
 */
export function moveSignature(input: MoveInput & { currentPlayerId: string | null }): string {
  const cardKey = (cards: Card[]) => cards.map((card) => `${card.suit}${card.value}${card.rule}${card.alwaysPlayable ? "!" : ""}`).join(",");
  const top = input.discardPile[input.discardPile.length - 1];
  return [
    input.currentPlayerId ?? "",
    input.discardPile.length,
    top ? cardKey([top]) : "",
    cardKey(input.hand),
    cardKey(input.faceUp),
    input.deckCount === 0 ? "0" : "n",
  ].join("|");
}
