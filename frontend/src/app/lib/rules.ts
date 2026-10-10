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
 * The cards the player can play from right now. Hand when it has cards (plus face-up when mixing is allowed and
 * the draw pile is empty); otherwise face-up. Face-down cards are never included: a blind flip is always allowed.
 */
export function playableSourceCards(input: MoveInput): Card[] {
  if (input.hand.length > 0) {
    const mixing = input.allowMixedHandAndFaceUpWhenDeckEmpty && input.deckCount === 0;
    return mixing ? [...input.hand, ...input.faceUp] : input.hand;
  }
  return input.faceUp;
}

/**
 * True when it is this player's turn, the discard pile has cards and none of the player's candidate cards can be
 * played on it. In that case the table selects the pick-up. Face-down only (or nothing) is never forced: a flip is allowed.
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
