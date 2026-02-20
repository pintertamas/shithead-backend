export default function FaceDownCount({ count }: { count: number }) {
  return (
    <div className="card">
      <div style={{ fontSize: 12, color: "var(--ink-dim)" }}>Face Down</div>
      <div className="pile-count">{count}</div>
    </div>
  );
}

