import { Card } from "../api/game";

export default function Pile({ title, count, cards }: { title: string; count: number; cards?: Card[] }) {
  return (
    <div className="card pile">
      <div style={{ fontSize: 12, color: "var(--ink-dim)" }}>{title}</div>
      {cards && cards.length > 0 && (
        <div className="card-tile pile-top-card">
          <div style={{ fontWeight: 700 }}>{cards[cards.length - 1].value}</div>
          <div style={{ fontSize: 12, color: "var(--ink-dim)" }}>{cards[cards.length - 1].suit}</div>
        </div>
      )}
      <div className="pile-count">{count}</div>
    </div>
  );
}

