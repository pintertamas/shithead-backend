import type { Card, GameEvent } from "../api/game";
import { cardRank, suitSymbol } from "../components/CardFace";

function cardsText(cards: Card[]): string {
  return cards.map((card) => `${cardRank(card.value)}${suitSymbol(card.suit)}`).join(" ");
}

function cardCountText(count: number): string {
  return `(${count} ${count === 1 ? "card" : "cards"})`;
}

/** Builds the human sentence for one structured feed event. */
export function describeEvent(event: GameEvent): string {
  const who = event.username || "Someone";
  switch (event.type) {
    case "PLAYED":
      return `${who} played ${cardsText(event.cards)}`;
    case "PLAYED_AGAIN":
      return `${who} gets another turn`;
    case "REVERSED":
      return `${who} reversed the turn order`;
    case "BURNED":
      return `${who} burned the pile ${cardCountText(event.count)}`;
    case "PICKED_UP":
      return `${who} picked up the pile ${cardCountText(event.count)}`;
    case "FAILED_FLIP":
      return `${who} flipped ${cardsText(event.cards)} and picked up the pile ${cardCountText(event.count)}`;
    case "FAILED_PLAY":
      return `${who}'s face-up play (${cardsText(event.cards)}) failed and they picked up the pile ${cardCountText(event.count)}`;
    case "READY":
      return `${who} is ready`;
    case "OUT":
      return `${who} is out`;
    case "FINISHED":
      return `${who} is the shithead`;
    default:
      // Types added by a newer backend still get a readable line.
      return `${who} made a move`;
  }
}
