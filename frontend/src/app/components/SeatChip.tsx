import { CSSProperties, useEffect, useRef } from "react";
import { createPortal } from "react-dom";
import { PlayerState } from "../api/game";
import { CardFaceContent, cardRank, isRedSuit } from "./CardFace";
import ChatBubble from "./ChatBubble";
import "../styles/overlays.css";
import "../styles/table-mobile.css";

/** Phones only: other players who are not the neighbours of the viewer are shown as small chips. */
type ChipProps = {
  player: PlayerState;
  isCurrentTurn: boolean;
  isNext: boolean;
  chatBubble?: { text: string; ts: number } | null;
  onOpen: (playerId: string) => void;
};

const CHIP_FAN_LIMIT = 3;

export function SeatChip({ player, isCurrentTurn, isNext, chatBubble, onOpen }: ChipProps) {
  const backs = Math.min(CHIP_FAN_LIMIT, player.handCount);
  const out = player.handCount === 0 && player.faceDownCount === 0 && player.faceUp.length === 0;
  const classes = ["seat-chip-wrap"];
  if (isCurrentTurn) classes.push("seat-chip-playing");
  if (isNext) classes.push("seat-next");
  if (out) classes.push("seat-chip-out");
  return (
    <div className={classes.join(" ")} data-seat-id={player.playerId}>
      {chatBubble && <ChatBubble key={chatBubble.ts} text={chatBubble.text} />}
      <button
        type="button"
        className="seat-chip"
        onClick={() => onOpen(player.playerId)}
        aria-label={`${player.username}: ${player.handCount} in hand, ${player.faceDownCount} face-down, ${player.faceUp.length} face-up${isCurrentTurn ? ", playing now" : ""}. Show cards`}
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
        {isCurrentTurn && <span className="seat-chip-turn">Playing</span>}
      </button>
    </div>
  );
}

type PeekProps = {
  player: PlayerState;
  isCurrentTurn: boolean;
  onClose: () => void;
};

/** Centred dialog with an opponent's face-up cards, enlarged. Closes on Escape, backdrop tap or the close button. */
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
        <div className="seat-peek-head">
          <strong className="seat-peek-name">{player.username}</strong>
          <span className="elo-badge">{Math.round(player.eloScore)}</span>
          {isCurrentTurn && <span className="seat-turn">Playing</span>}
        </div>
        <div className="seat-peek-counts">{player.handCount} in hand · {player.faceDownCount} face-down</div>
        <div className="seat-peek-cards">
          {player.faceUp.length === 0 ? (
            <p className="seat-peek-empty">No face-up cards.</p>
          ) : player.faceUp.map((card, index) => (
            <div
              key={`${card.suit}-${card.value}-${index}`}
              className={`playing-card face-up-card seat-peek-card${isRedSuit(card.suit) ? " red-card" : ""}`}
              aria-label={`${cardRank(card.value)} of ${card.suit}`}
            >
              <CardFaceContent card={card} />
            </div>
          ))}
        </div>
      </div>
    </>,
    document.body
  );
}
