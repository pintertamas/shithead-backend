import { PlayerState } from "../api/game";
import FaceUp from "./FaceUp";
import FaceDownCount from "./FaceDownCount";

export default function PlayerPanel({ player }: { player: PlayerState }) {
  return (
    <div className="player-item" style={{ alignItems: "flex-start", gap: 12 }}>
      <div>
        <div style={{ fontWeight: 700 }}>{player.username}</div>
        <div style={{ fontSize: 12, color: "var(--ink-dim)" }}>
          Hand: {player.handCount}
        </div>
      </div>
      {player.faceUp.length > 0 && (
        <div style={{ maxWidth: 220 }}>
          <FaceUp cards={player.faceUp} />
        </div>
      )}
      {player.faceDownCount > 0 && <FaceDownCount count={player.faceDownCount} />}
    </div>
  );
}

