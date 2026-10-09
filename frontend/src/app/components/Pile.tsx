import { CSSProperties } from "react";
import { Card } from "../api/game";
import { CardFaceContent, cardRank, isRedSuit } from "./CardFace";
import PeekWrap from "./PeekWrap";

const MAX_DRAW_LAYERS = 6;

function faceClass(card: Card, extra: string) {
  return `playing-card face-up-card${isRedSuit(card.suit) ? " red-card" : ""} ${extra}`;
}

function cardLabel(card: Card) {
  return `${cardRank(card.value)} of ${card.suit}`;
}

function DrawPile({ title, count, fxAnchor }: { title: string; count: number; fxAnchor?: string }) {
  const layers = count <= 0 ? 0 : Math.min(MAX_DRAW_LAYERS, 1 + Math.floor(count / 8));
  const stackLayers = Math.max(layers, 1);
  const pileElement = (
    <div
      className={`card pile pile-draw${count === 0 ? " pile-empty" : ""}`}
      data-fx={fxAnchor}
      aria-label={`${title}, ${count} ${count === 1 ? "card" : "cards"} left`}
    >
      <div style={{ fontSize: 12, color: "var(--ink-dim)" }}>{title}</div>
      <div className="draw-stack" style={{ "--stack-layers": stackLayers } as CSSProperties}>
        {Array.from({ length: layers }, (_, layer) => (
          <div
            key={layer}
            className="playing-card face-down-card draw-layer"
            style={{ "--layer": layer, "--layers": layers } as CSSProperties}
            aria-hidden="true"
          />
        ))}
        <span className="draw-count">{count}</span>
      </div>
    </div>
  );

  return (
    <PeekWrap
      className="pile-peek-wrap"
      label={`Show ${title.toLowerCase()} count`}
      toggleText="View pile"
      pressToOpen
      popover={<div className="peek-note">Draw pile: {count} {count === 1 ? "card" : "cards"}</div>}
    >
      {pileElement}
    </PeekWrap>
  );
}

function PileContents({ cards }: { cards: Card[] }) {
  return (
    <div className="pile-contents">
      <div className="pile-contents-title">
        Discard pile · {cards.length} {cards.length === 1 ? "card" : "cards"}
        <span>newest last</span>
      </div>
      <div className="pile-contents-cards">
        {cards.map((card, index) => (
          <div key={index} className={faceClass(card, "pile-contents-card")} aria-label={cardLabel(card)}>
            <CardFaceContent card={card} />
          </div>
        ))}
      </div>
    </div>
  );
}

export default function Pile({
  title,
  count,
  cards,
  onClick,
  selectable = false,
  selected = false,
  disabled = false,
  variant = "discard",
  fxAnchor
}: {
  title: string;
  count: number;
  cards?: Card[];
  onClick?: () => void;
  selectable?: boolean;
  selected?: boolean;
  disabled?: boolean;
  variant?: "discard" | "draw";
  /** Marks the element so table animations can find it. */
  fxAnchor?: string;
}) {
  if (variant === "draw") return <DrawPile title={title} count={count} fxAnchor={fxAnchor} />;

  const pileCards = cards || [];
  const topCard = pileCards[pileCards.length - 1];
  const topIsTransparent = topCard?.rule === "TRANSPARENT";
  // First non-transparent card beneath the run of transparent cards on top (-1 when there is none).
  let beneathIndex = -1;
  if (topIsTransparent) {
    let index = pileCards.length - 1;
    while (index >= 0 && pileCards[index].rule === "TRANSPARENT") index--;
    beneathIndex = index;
  }
  const beneathCard = beneathIndex >= 0 ? pileCards[beneathIndex] : undefined;

  const pileElement = (
    <div
      className={`card pile${pileCards.length === 0 ? " pile-empty" : ""}${topIsTransparent ? " pile-with-transparent" : ""}${selectable ? " pile-selectable" : ""}${selected ? " pile-selected" : ""}${disabled ? " pile-disabled" : ""}`}
      data-fx={fxAnchor}
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
      <div
        className="pile-visible-cards"
        aria-label={topCard ? (beneathCard ? "Transparent card and the card it covers" : "Top card") : undefined}
      >
        {!topCard && <div className="pile-slot" aria-hidden="true" />}
        {topCard && beneathCard && (
          <div className="pile-overlap">
            <div className={faceClass(beneathCard, "pile-under-card")} aria-label={`${cardLabel(beneathCard)}, beneath transparent cards`}>
              <CardFaceContent card={beneathCard} />
            </div>
            <div className={faceClass(topCard, "pile-over-card pile-transparent-card")} aria-label={`${cardLabel(topCard)}, transparent`}>
              <CardFaceContent card={topCard} />
            </div>
          </div>
        )}
        {topCard && !beneathCard && (
          <div className={faceClass(topCard, "pile-top-card")} aria-label={cardLabel(topCard)}>
            <CardFaceContent card={topCard} />
          </div>
        )}
      </div>
      <div className="pile-count">{count}</div>
    </div>
  );

  // Both states use the same wrapper so the discard pile keeps its size when it is empty. The peek
  // popover only exists while there are cards to show.
  if (pileCards.length === 0) return <div className="pile-peek-wrap">{pileElement}</div>;

  return (
    <PeekWrap
      className="pile-peek-wrap"
      label={`View all ${title.toLowerCase()} cards`}
      toggleText="View pile"
      popover={<PileContents cards={pileCards} />}
    >
      {pileElement}
    </PeekWrap>
  );
}
