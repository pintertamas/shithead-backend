import { Card } from "../api/game";

export default function FaceUp({ cards }: { cards: Card[] }) {
  return (
    <div className="hand">
      {cards.map((card, idx) => (
        <div key={`${card.suit}-${card.value}-${idx}`} className="card-tile">
          <div style={{ fontWeight: 700 }}>{card.value}</div>
          <div style={{ fontSize: 12, color: "var(--ink-dim)" }}>{card.suit}</div>
        </div>
      ))}
    </div>
  );
}

