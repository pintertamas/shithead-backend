import { Card } from "../api/game";

type Props = {
  cards: Card[];
  selected?: number[];
  onToggle?: (idx: number) => void;
};

export default function Hand({ cards, selected = [], onToggle }: Props) {
  return (
    <div className="hand">
      {cards.map((card, idx) => {
        const isSelected = selected.includes(idx);
        return (
          <div
            key={`${card.suit}-${card.value}-${idx}`}
            className={`card-tile${isSelected ? " card-selected" : ""}${onToggle ? " card-selectable" : ""}`}
            onClick={() => onToggle?.(idx)}
          >
            <div style={{ fontWeight: 700 }}>{card.value}</div>
            <div style={{ fontSize: 12, color: "var(--ink-dim)" }}>{card.suit}</div>
          </div>
        );
      })}
    </div>
  );
}
