import { Card } from "../api/game";

export default function Pile({ title, count, cards }: { title: string; count: number; cards?: Card[] }) {
  return (
    <div className="card pile">
      <div style={{ fontSize: 12, color: "var(--ink-dim)" }}>{title}</div>
      <div className="pile-count">{count}</div>
      {cards && cards.length > 0 && (
        <div className="hand">
          {cards.slice(-4).map((card, idx) => (
            <div key={`${card.suit}-${card.value}-${idx}`} className="card-tile">
              <div style={{ fontWeight: 700 }}>{card.value}</div>
              <div style={{ fontSize: 12, color: "var(--ink-dim)" }}>{card.suit}</div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

