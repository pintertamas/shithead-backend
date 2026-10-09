import "../styles/starter-picker.css";
import { PlayerState } from "../api/game";

type Props = {
  players: PlayerState[];
  currentPlayerId: string | null;
  isOwner: boolean;
  disabled: boolean;
  onPick: (starterId: string) => void;
};

/** Mirrors the backend rule: the lowest rating starts, and among equal ratings the first player in the list. */
export function lowestRatedPlayerId(players: PlayerState[]): string | null {
  let lowest: PlayerState | null = null;
  for (const player of players) {
    if (!lowest || player.eloScore < lowest.eloScore) lowest = player;
  }
  return lowest?.playerId ?? null;
}

export default function StarterPicker({ players, currentPlayerId, isOwner, disabled, onPick }: Props) {
  const starter = players.find((player) => player.playerId === currentPlayerId);
  if (!starter) return null;
  const reason = starter.playerId === lowestRatedPlayerId(players) ? "lowest rating" : "chosen by the owner";

  return (
    <div className="starter-picker">
      <p className="starter-picker-current">
        <span>Starting player:</span> <strong>{starter.username}</strong> <span className="starter-picker-reason">({reason})</span>
      </p>
      {isOwner && (
        <div className="starter-picker-chips" role="group" aria-label="Choose the starting player">
          {players.map((player) => {
            const active = player.playerId === starter.playerId;
            return (
              <button
                key={player.playerId}
                type="button"
                className={`starter-picker-chip${active ? " active" : ""}`}
                aria-pressed={active}
                disabled={disabled || active}
                onClick={() => onPick(player.playerId)}
              >
                {player.username}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
