import { Card } from "../api/game";

function cardRank(value: number) {
  return ({ 11: "J", 12: "Q", 13: "K", 14: "A" } as Record<number, string>)[value] || String(value);
}

function suitSymbol(suit: string) {
  return ({ CLUBS: "♣", DIAMONDS: "♦", HEARTS: "♥", SPADES: "♠" } as Record<string, string>)[suit] || suit;
}

export default function Pile({
  title,
  count,
  cards,
  onClick,
  selectable = false,
  selected = false,
  disabled = false
}: {
  title: string;
  count: number;
  cards?: Card[];
  onClick?: () => void;
  selectable?: boolean;
  selected?: boolean;
  disabled?: boolean;
}) {
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
    <div
      className={`card pile${hasTransparentTop ? " pile-with-transparent" : ""}${selectable ? " pile-selectable" : ""}${selected ? " pile-selected" : ""}${disabled ? " pile-disabled" : ""}`}
      role={selectable ? "button" : undefined}
      tabIndex={selectable && !disabled ? 0 : undefined}
      aria-label={selectable ? `${title}, ${count} cards${disabled ? ", unavailable" : ""}` : undefined}
      aria-pressed={selectable ? selected : undefined}
      aria-disabled={selectable ? disabled : undefined}
      onClick={selectable && !disabled ? onClick : undefined}
      onKeyDown={selectable && !disabled ? (event) => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          onClick?.();
        }
      } : undefined}
    >
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
                <span className="playing-card-corner-opposite" aria-hidden="true">{cardRank(card.value)}<br />{suitSymbol(card.suit)}</span>
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
