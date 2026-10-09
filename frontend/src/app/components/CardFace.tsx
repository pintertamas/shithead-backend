import { Card } from "../api/game";

export function cardRank(value: number) {
  return ({ 11: "J", 12: "Q", 13: "K", 14: "A" } as Record<number, string>)[value] || String(value);
}

export function suitSymbol(suit: string) {
  // U+FE0E asks for the text (not emoji) presentation of the suit, so iOS and desktop draw the same symbol.
  return ({ CLUBS: "♣︎", DIAMONDS: "♦︎", HEARTS: "♥︎", SPADES: "♠︎" } as Record<string, string>)[suit] || suit;
}

export function isRedSuit(suit: string) {
  return suit === "HEARTS" || suit === "DIAMONDS";
}

/** Corner indexes and centre pip of a face-up card. Wrap in a `.playing-card` element. */
export function CardFaceContent({ card }: { card: Card }) {
  const rank = cardRank(card.value);
  const suit = suitSymbol(card.suit);
  return (
    <>
      <span className="playing-card-corner">{rank}<br />{suit}</span>
      <span className="playing-card-corner-opposite" aria-hidden="true">{rank}<br />{suit}</span>
      <span className="playing-card-center">{suit}</span>
    </>
  );
}
