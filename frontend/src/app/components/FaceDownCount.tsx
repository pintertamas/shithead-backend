export default function FaceDownCount({ count, selectable = false, selected = [], onToggle }: {
  count: number;
  selectable?: boolean;
  selected?: number[];
  onToggle?: (index: number) => void;
}) {
  return (
    <div className="card face-down-cards">
      <div style={{ fontSize: 12, color: "var(--ink-dim)" }}>Face Down</div>
      <div className="hand">
        {Array.from({ length: count }, (_, idx) => selectable ? (
          <button
            type="button"
            key={idx}
            className={`card-tile card-back card-selectable${selected.includes(idx) ? " card-selected" : ""}`}
            onClick={() => onToggle?.(idx)}
            aria-pressed={selected.includes(idx)}
            aria-label={`Face down card ${idx + 1}${selected.includes(idx) ? ", selected" : ""}`}
          />
        ) : <div key={idx} className="card-tile card-back" aria-label="Face down card" />)}
      </div>
      <div className="pile-count">{count}</div>
    </div>
  );
}
