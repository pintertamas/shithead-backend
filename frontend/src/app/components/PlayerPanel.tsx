import { PlayerState } from "../api/game";
import FaceUp from "./FaceUp";

export default function PlayerPanel({ player }: { player: PlayerState }) {
  return (
    <div className="player-item" style={{ alignItems: "flex-start", gap: 12 }}>
      <div>
        <div style={{ fontWeight: 700 }}>{player.username}</div>
        <div style={{ fontSize: 12, color: "var(--ink-dim)" }}>
          Hand: {player.handCount} | Face down: {player.faceDownCount}
        </div>
      </div>
      {player.faceUp.length > 0 && (
        <div style={{ maxWidth: 220 }}>
          <FaceUp cards={player.faceUp} />
        </div>
      )}
    </div>
  );
}

