import { Card } from "../api/game";

function cardRank(value: number) {
  return ({ 11: "J", 12: "Q", 13: "K", 14: "A" } as Record<number, string>)[value] || String(value);
}

function suitSymbol(suit: string) {
  return ({ CLUBS: "♣", DIAMONDS: "♦", HEARTS: "♥", SPADES: "♠" } as Record<string, string>)[suit] || suit;
}

export default function Pile({ title, count, cards }: { title: string; count: number; cards?: Card[] }) {
  const pileCards = cards || [];
  const topCard = pileCards[pileCards.length - 1];
  let transparentStart = pileCards.length - 1;
  while (transparentStart > 0 && pileCards[transparentStart - 1].rule === "TRANSPARENT") {
    transparentStart--;
  }
  const hasTransparentTop = topCard?.rule === "TRANSPARENT";
  const hasUnderlyingCard = hasTransparentTop && transparentStart > 0;
  const visibleCards = hasTransparentTop
    ? [...(hasUnderlyingCard ? [pileCards[transparentStart - 1]] : []), ...pileCards.slice(transparentStart)]
    : topCard ? [topCard] : [];

  return (
    <div className={`card pile${hasTransparentTop ? " pile-with-transparent" : ""}`}>
      <div style={{ fontSize: 12, color: "var(--ink-dim)" }}>{title}</div>
      {visibleCards.length > 0 && (
        <div className="pile-visible-cards" aria-label={hasTransparentTop ? "Latest transparent cards and the card beneath them" : "Top card"}>
          {visibleCards.map((card, index) => {
            const isUnderlyingCard = hasUnderlyingCard && index === 0;
            const isRed = card.suit === "HEARTS" || card.suit === "DIAMONDS";
            return (
              <div
                key={`${card.suit}-${card.value}-${index}`}
                className={`playing-card face-up-card${isRed ? " red-card" : ""}${isUnderlyingCard ? " pile-under-card" : index > 0 && hasTransparentTop ? " pile-transparent-card" : " pile-top-card"}`}
                style={{ zIndex: index + 1 }}
                aria-label={`${cardRank(card.value)} of ${card.suit}${isUnderlyingCard ? ", beneath transparent cards" : ""}`}
              >
                <span className="playing-card-corner">{cardRank(card.value)}<br />{suitSymbol(card.suit)}</span>
                <span className="playing-card-center">{suitSymbol(card.suit)}</span>
              </div>
            );
          })}
        </div>
      )}
      <div className="pile-count">{count}</div>
    </div>
  );
}