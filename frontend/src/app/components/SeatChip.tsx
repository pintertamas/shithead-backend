import { CSSProperties, useEffect, useRef } from "react";
import { createPortal } from "react-dom";
import { PlayerState } from "../api/game";
import ChatBubble from "./ChatBubble";
import SeatTableView from "./SeatTableView";
import "../styles/overlays.css";
import "../styles/table-mobile.css";
import "../styles/seat-indicators.css";
import "../styles/voice-speaking.css";

/** Phones only: other players who are not the neighbours of the viewer are shown as small chips. */
type ChipProps = {
  player: PlayerState;
  isCurrentTurn: boolean;
  isNext: boolean;
  /** In this game's voice call and speaking right now. Draws the green speaking ring (voice-speaking.css). */
  speaking?: boolean;
  chatBubble?: { text: string; ts: number } | null;
  onOpen: (playerId: string) => void;
};

const CHIP_FAN_LIMIT = 3;

export function SeatChip({ player, isCurrentTurn, isNext, speaking = false, chatBubble, onOpen }: ChipProps) {
  const backs = Math.min(CHIP_FAN_LIMIT, player.handCount);
  const out = player.handCount === 0 && player.faceDownCount === 0 && player.faceUp.length === 0;
  const classes = ["seat-chip-wrap"];
  if (isCurrentTurn) classes.push("seat-chip-playing");
  if (isNext) classes.push("seat-next");
  if (out) classes.push("seat-chip-out");
  if (speaking) classes.push("seat-speaking");
  return (
    <div className={classes.join(" ")} data-seat-id={player.playerId}>
      {chatBubble && <ChatBubble key={chatBubble.ts} text={chatBubble.text} />}
      <button
        type="button"
        className="seat-chip"
        onClick={() => onOpen(player.playerId)}
        aria-label={`${player.username}: ${player.handCount} in hand, ${player.faceDownCount} face-down, ${player.faceUp.length} face-up${isCurrentTurn ? ", current turn" : ""}${isNext ? ", plays next" : ""}${speaking ? ", speaking" : ""}. Show cards`}
      >
        <span className="seat-chip-name">
          {player.username}
        </span>
        <span className="seat-chip-line">
          <span className="seat-chip-fan" aria-hidden="true">
            {Array.from({ length: backs }, (_, index) => (
              <span key={index} className="seat-chip-back" style={{ "--chip-i": index } as CSSProperties} />
            ))}
          </span>
          <span className="seat-chip-counts">{player.handCount}·{player.faceDownCount}</span>
        </span>
      </button>
    </div>
  );
}

type PeekProps = {
  player: PlayerState;
  isCurrentTurn: boolean;
  onClose: () => void;
};

/** Centred dialog with the opponent's whole seat (hand, face-down and face-up cards). Closes on Escape, backdrop tap or the close button. */
export function SeatPeek({ player, isCurrentTurn, onClose }: PeekProps) {
  const dialogRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKeyDown);
    dialogRef.current?.focus({ preventScroll: true });
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  return createPortal(
    <>
      <div className="peek-overlay-backdrop" aria-hidden="true" onClick={onClose} />
      <div
        ref={dialogRef}
        className="peek-overlay peek-overlay-mobile seat-peek"
        role="dialog"
        aria-modal="true"
        aria-label={`${player.username}'s cards`}
        tabIndex={-1}
      >
        <button type="button" className="peek-close" aria-label="Close" onClick={onClose}>×</button>
        <SeatTableView player={player} isCurrentTurn={isCurrentTurn} />
      </div>
    </>,
    document.body
  );
}
