export default function ShitheadModal({ name, onClose }: { name: string; onClose: () => void }) {
  return (
    <div className="modal-backdrop">
      <div className="glass modal">
        <div className="badge">Game Over</div>
        <h2 className="title">Shithead: {name}</h2>
        <p style={{ color: "var(--ink-dim)" }}>
          Time to head to the leaderboard.
        </p>
        <button className="button" onClick={onClose}>
          View Leaderboard
        </button>
      </div>
    </div>
  );
}

