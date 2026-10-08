import { Card } from "../api/game";

export default function FaceUp({ cards, selected = [], onToggle }: {
  cards: Card[];
  selected?: number[];
  onToggle?: (index: number) => void;
}) {
  return (
    <div className="hand">
      {cards.map((card, idx) => (
        <button
          type="button"
          key={`${card.suit}-${card.value}-${idx}`}
          className={`card-tile${selected.includes(idx) ? " card-selected" : ""}${onToggle ? " card-selectable" : ""}`}
          onClick={() => onToggle?.(idx)}
          aria-pressed={selected.includes(idx)}
          aria-label={`${card.value} of ${card.suit}${selected.includes(idx) ? ", selected" : ""}`}
        >
          <div style={{ fontWeight: 700 }}>{card.value}</div>
          <div style={{ fontSize: 12, color: "var(--ink-dim)" }}>{card.suit}</div>
        </button>
      ))}
    </div>
  );
}

