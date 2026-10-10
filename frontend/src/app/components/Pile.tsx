import { CSSProperties, ReactNode } from "react";
import { Card } from "../api/game";
import { CardFaceContent, cardRank, isRedSuit } from "./CardFace";
import PeekWrap from "./PeekWrap";
import "../styles/piles.css";

const MAX_DRAW_LAYERS = 6;

function faceClass(card: Card, extra: string) {
  return `playing-card face-up-card${isRedSuit(card.suit) ? " red-card" : ""} ${extra}`;
}

function cardLabel(card: Card) {
  return `${cardRank(card.value)} of ${card.suit}`;
}

function countText(count: number) {
  return `${count} ${count === 1 ? "card" : "cards"}`;
}

/** Discard pile contents. On phones (dialog) it is a titled dialog with a grid; on desktop a compact popover. */
function PileContents({ title, cards, dialog }: { title: string; cards: Card[]; dialog: boolean }) {
  const heading = `${title} · ${countText(cards.length)}`;
  if (dialog) {
    return (
      <div className="pile-dialog">
        <div className="pile-dialog-head">{heading}</div>
        <div className="pile-dialog-body">
          <div className="pile-dialog-grid">
            {cards.map((card, index) => {
              const isTop = index === cards.length - 1;
              return (
                <div key={index} className="pile-dialog-cell">
                  <div className={faceClass(card, "pile-dialog-card")} aria-label={`${cardLabel(card)}${isTop ? ", top card" : ""}`}>
                    <CardFaceContent card={card} />
                  </div>
                  {isTop && <span className="pile-dialog-top" aria-hidden="true">Top</span>}
                </div>
              );
            })}
          </div>
        </div>
      </div>
    );
  }
  return (
    <div className="pile-contents">
      <div className="pile-contents-title">
        {heading}
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

/** The shared box: title, card area of fixed size, and the count underneath. Both piles use it. */
function PileBox({ variant, title, count, empty, fxAnchor, onClick, selectable = false, selected = false, disabled = false, label, children, extraClass = "" }: {
  variant: "draw" | "discard";
  title: string;
  count: number;
  empty: boolean;
  fxAnchor?: string;
  onClick?: () => void;
  selectable?: boolean;
  selected?: boolean;
  disabled?: boolean;
  label?: string;
  children: ReactNode;
  extraClass?: string;
}) {
  return (
    <div
      className={`card pile pile-box pile-${variant}${empty ? " pile-empty" : ""}${extraClass}${selectable ? " pile-selectable" : ""}${selected ? " pile-selected" : ""}${disabled ? " pile-disabled" : ""}`}
      data-fx={fxAnchor}
      role={selectable ? "button" : undefined}
      tabIndex={selectable && !disabled ? 0 : undefined}
      aria-label={label}
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
      <div className="pile-title">{title}</div>
      <div className="pile-body">{children}</div>
      <div className="pile-count">{count}</div>
    </div>
  );
}

function DrawPile({ title, count, fxAnchor }: { title: string; count: number; fxAnchor?: string }) {
  const layers = count <= 0 ? 0 : Math.min(MAX_DRAW_LAYERS, 1 + Math.floor(count / 8));
  const stackLayers = Math.max(layers, 1);
  const pileElement = (
    <PileBox
      variant="draw"
      title={title}
      count={count}
      empty={count === 0}
      fxAnchor={fxAnchor}
      label={`${title}, ${countText(count)} left`}
    >
      <div className="draw-stack" style={{ "--stack-layers": stackLayers } as CSSProperties}>
        {Array.from({ length: layers }, (_, layer) => (
          <div
            key={layer}
            className="playing-card face-down-card draw-layer"
            style={{ "--layer": layer, "--layers": layers } as CSSProperties}
            aria-hidden="true"
          />
        ))}
      </div>
    </PileBox>
  );

  return (
    <PeekWrap
      className="pile-peek-wrap"
      label={`Show ${title.toLowerCase()} count`}
      toggleText="View pile"
      pressToOpen
      dialogClassName="pile-dialog-overlay"
      popover={(dialog) => (dialog ? (
        <div className="pile-dialog">
          <div className="pile-dialog-head">{title} · {countText(count)}</div>
          <div className="pile-dialog-body">
            <p className="pile-dialog-note">{count === 0 ? "No cards left to draw." : `${countText(count)} left to draw. Their faces are hidden.`}</p>
          </div>
        </div>
      ) : (
        <div className="peek-note">{title}: {countText(count)}</div>
      ))}
    >
      {pileElement}
    </PeekWrap>
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
    <PileBox
      variant="discard"
      title={title}
      count={count}
      empty={pileCards.length === 0}
      fxAnchor={fxAnchor}
      selectable={selectable}
      selected={selected}
      disabled={disabled}
      onClick={onClick}
      extraClass={topIsTransparent ? " pile-with-transparent" : ""}
      label={selectable ? `${title}, ${count} cards${disabled ? ", unavailable" : ""}` : undefined}
    >
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
    </PileBox>
  );

  // Both states use the same wrapper so the discard pile keeps its size when it is empty. The peek
  // popover only exists while there are cards to show.
  if (pileCards.length === 0) return <div className="pile-peek-wrap">{pileElement}</div>;

  return (
    <PeekWrap
      className="pile-peek-wrap"
      label={`View all ${title.toLowerCase()} cards`}
      toggleText="View pile"
      dialogClassName="pile-dialog-overlay"
      popover={(dialog) => <PileContents title={title} cards={pileCards} dialog={dialog} />}
    >
      {pileElement}
    </PeekWrap>
  );
}
