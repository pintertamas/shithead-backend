import { CSSProperties, useEffect, useRef } from "react";
import { createPortal } from "react-dom";
import { Card, PlayerState } from "../api/game";
import ChatBubble from "./ChatBubble";
import SeatTableView from "./SeatTableView";
import { CardFaceContent, isRedSuit } from "./CardFace";
import "../styles/overlays.css";
import "../styles/table-mobile.css";
import "../styles/seat-indicators.css";
import "../styles/voice-speaking.css";
import "../styles/seat-chip-deck.css";

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

/** Mini cards drawn per group. Larger groups are still counted in the aria-label, just not drawn in full. */
const MINI_STACK_LIMIT = 3;

/** Which group the player can play from now: hand first, then face-up, then face-down (the server's source order). */
type PlayGroup = "hand" | "faceUp" | "faceDown";

function playGroup(player: PlayerState): PlayGroup | null {
  if (player.handCount > 0) return "hand";
  if (player.faceUp.length > 0) return "faceUp";
  if (player.faceDownCount > 0) return "faceDown";
  return null;
}

const GROUP_WORDS: Record<PlayGroup, string> = { hand: "hand", faceUp: "face-up cards", faceDown: "face-down cards" };

/** The screen reader's summary of the chip. Counts are words here only: the chip itself shows cards, not numbers. */
function chipLabel(player: PlayerState, group: PlayGroup | null, isCurrentTurn: boolean, isNext: boolean, speaking: boolean): string {
  const parts: string[] = [];
  if (group === null) {
    parts.push("out of cards");
  } else {
    parts.push(player.handCount > 0 ? `${player.handCount} in hand` : "no cards in hand");
    if (player.faceUp.length > 0) parts.push(`${player.faceUp.length} face-up`);
    if (player.faceDownCount > 0) parts.push(`${player.faceDownCount} face-down`);
    parts.push(`can play from ${GROUP_WORDS[group]}`);
  }
  if (isCurrentTurn) parts.push("current turn");
  if (isNext) parts.push("plays next");
  if (speaking) parts.push("speaking");
  return `${player.username}: ${parts.join(", ")}. Show cards`;
}

function MiniBacks({ count, className }: { count: number; className: string }) {
  return (
    <>
      {Array.from({ length: Math.min(MINI_STACK_LIMIT, count) }, (_, index) => (
        <span key={index} className={`seat-chip-mini-back ${className}`} style={{ "--chip-i": index } as CSSProperties} />
      ))}
    </>
  );
}

function MiniFace({ card }: { card: Card }) {
  return (
    <span className={`playing-card seat-chip-mini-face${isRedSuit(card.suit) ? " red-card" : ""}`}>
      <CardFaceContent card={card} />
    </span>
  );
}

export function SeatChip({ player, isCurrentTurn, isNext, speaking = false, chatBubble, onOpen }: ChipProps) {
  const group = playGroup(player);
  const out = group === null;
  /** Groups other than the playable one are dimmed; nothing is dimmed when the player is out. */
  const dim = (target: PlayGroup) => (group !== null && group !== target ? " is-dim" : "");
  const classes = ["seat-chip-wrap"];
  if (isCurrentTurn) classes.push("seat-chip-playing");
  if (isNext) classes.push("seat-next");
  if (out) classes.push("seat-chip-out");
  if (speaking) classes.push("seat-speaking");
  const faceUpShown = player.faceUp.slice(-MINI_STACK_LIMIT);
  return (
    <div className={classes.join(" ")} data-seat-id={player.playerId}>
      {chatBubble && <ChatBubble key={chatBubble.ts} text={chatBubble.text} />}
      <button
        type="button"
        className="seat-chip"
        onClick={() => onOpen(player.playerId)}
        aria-label={chipLabel(player, group, isCurrentTurn, isNext, speaking)}
      >
        <span className="seat-chip-name" aria-hidden="true">
          {player.username}
        </span>
        <span className="seat-chip-deck" aria-hidden="true">
          <span className={`seat-chip-hand${dim("hand")}`}>
            {player.handCount > 0
              ? <MiniBacks count={player.handCount} className="seat-chip-mini-back-hand" />
              : <span className="seat-chip-hand-empty" />}
          </span>
          <span className="seat-chip-table">
            {player.faceDownCount > 0 && (
              <span className={`seat-chip-down${dim("faceDown")}`}>
                <MiniBacks count={player.faceDownCount} className="seat-chip-mini-back-down" />
              </span>
            )}
            {faceUpShown.map((card, index) => (
              <span key={`${card.suit}-${card.value}-${index}`} className={`seat-chip-up${dim("faceUp")}`}
                style={{ "--chip-i": index } as CSSProperties}>
                <MiniFace card={card} />
              </span>
            ))}
            {out && <span className="seat-chip-out-mark">Out</span>}
          </span>
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
