import { CSSProperties, useRef } from "react";
import { Card, PlayerState } from "../api/game";
import { CardFaceContent, cardRank } from "./CardFace";
import PeekWrap from "./PeekWrap";
import ChatBubble from "./ChatBubble";
import SeatTableView from "./SeatTableView";
import "../styles/player-panel.css";
import "../styles/seat-panels.css";
import "../styles/seat-indicators.css";

type Props = {
  player: PlayerState;
  /** Latest chat line from this player, shown as a bubble above the seat for a few seconds. */
  chatBubble?: { text: string; ts: number } | null;
  isCurrentTurn?: boolean;
  isNext?: boolean;
  /** Phones: a smaller panel for the previous and next player. Face-up cards open on tap. */
  compact?: boolean;
  canSelectFaceUp?: boolean;
  canSelectFaceDown?: boolean;
  selectedFaceUp?: number[];
  selectedFaceDown?: number[];
  selectedHand?: number[];
  onToggleFaceUp?: (index: number) => void;
  onToggleFaceDown?: (index: number) => void;
  onToggleHand?: (index: number) => void;
};

/** Opponent hands are drawn as a fan of backs; larger hands are capped, the header keeps the exact count. */
const FAN_LIMIT = 10;

/**
 * Table slots for one player's stacks. A face-up slot holds the card that was dealt there (or null once
 * it has been played); a face-down slot is alive until the card behind it is played. Slots never move,
 * so the server's compacted lists are mapped onto them: server index = rank among occupied slots.
 */
type PanelLayout = { faceUp: (Card | null)[]; faceDown: boolean[] };

const sameCard = (a: Card, b: Card) => a.suit === b.suit && a.value === b.value;

/**
 * Matches the remaining face-up cards to their previous slots (longest common subsequence, so the order
 * the server reports is kept). Cards that are new (a swap during setup) fill the free slots in their gap.
 */
export function reconcileFaceUp(prev: (Card | null)[], next: Card[]): (Card | null)[] {
  const occupied = prev.flatMap((card, slot) => (card ? [slot] : []));
  const m = occupied.length;
  const n = next.length;
  const dp: number[][] = Array.from({ length: m + 1 }, () => new Array<number>(n + 1).fill(0));
  for (let i = m - 1; i >= 0; i--) {
    for (let j = n - 1; j >= 0; j--) {
      dp[i][j] = sameCard(prev[occupied[i]]!, next[j]) ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
    }
  }
  const matches: [number, number][] = [];
  let i = 0;
  let j = 0;
  while (i < m && j < n) {
    if (sameCard(prev[occupied[i]]!, next[j]) && dp[i][j] === dp[i + 1][j + 1] + 1) {
      matches.push([occupied[i], j]);
      i++;
      j++;
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      i++;
    } else {
      j++;
    }
  }

  const length = Math.max(prev.length, n);
  const result: (Card | null)[] = new Array<Card | null>(length).fill(null);
  for (const [slot, nextIndex] of matches) result[slot] = next[nextIndex];

  let prevSlot = -1;
  let prevNext = -1;
  for (const [slot, nextIndex] of [...matches, [length, n] as [number, number]]) {
    const free: number[] = [];
    for (let s = prevSlot + 1; s < slot; s++) if (!result[s]) free.push(s);
    const pending = next.slice(prevNext + 1, nextIndex);
    if (pending.length > free.length) {
      // Only reachable when the hand is dealt again without the panel being remounted: start over compactly.
      return Array.from({ length }, (_, s) => next[s] ?? null);
    }
    pending.forEach((card, k) => { result[free[k]] = card; });
    prevSlot = slot;
    prevNext = nextIndex;
  }
  return result;
}

/**
 * Face-down cards cannot be told apart, so only their slots are tracked. When the count drops, the slots
 * the owner last selected are the ones played; otherwise the right-most cards are taken.
 */
export function reconcileFaceDown(prev: boolean[], count: number, selectedRanks: number[]): boolean[] {
  const aliveSlots = prev.flatMap((alive, slot) => (alive ? [slot] : []));
  const alive = aliveSlots.length;
  if (count === alive) return prev;
  if (count > alive) {
    // Face-down cards are only ever removed during a game, so more of them means a new deal.
    return Array.from({ length: Math.max(prev.length, count) }, (_, slot) => slot < count);
  }
  const removeCount = alive - count;
  const selected = [...new Set(selectedRanks)].filter((rank) => rank >= 0 && rank < alive).map((rank) => aliveSlots[rank]);
  const removed = selected.length === removeCount ? selected : aliveSlots.slice(alive - removeCount);
  return prev.map((isAlive, slot) => isAlive && !removed.includes(slot));
}

/** Server index for each slot: the position of the card in the server's compacted list, or -1 when empty. */
function serverIndexes(occupied: boolean[]) {
  let next = 0;
  return occupied.map((isOccupied) => (isOccupied ? next++ : -1));
}

function VisibleCard({ card, index, selected, onToggle }: {
  card: Card;
  index: number;
  selected: boolean;
  onToggle?: (index: number) => void;
}) {
  const className = `playing-card face-up-card${selected ? " card-selected" : ""}${onToggle ? " card-selectable" : ""}`;
  const label = `${cardRank(card.value)} of ${card.suit}${selected ? ", selected" : ""}`;
  return onToggle ? (
    <button type="button" className={className} onClick={() => onToggle(index)} aria-pressed={selected} aria-label={label}>
      <CardFaceContent card={card} />
    </button>
  ) : <div className={className} aria-label={`${cardRank(card.value)} of ${card.suit}`}><CardFaceContent card={card} /></div>;
}

/** The fan always takes its height, so an empty hand leaves the rows below where they were. */
function OpponentFan({ count, name }: { count: number; name: string }) {
  if (count <= 0) return <div className="seat-fan-row" aria-label={`${name} has no cards in hand`} />;
  const drawn = Math.min(count, FAN_LIMIT);
  const middle = (drawn - 1) / 2;
  return (
    <div className="seat-fan-row">
      <div className="seat-fan" aria-hidden="true" style={{ "--fan-n": drawn } as CSSProperties}>
        {Array.from({ length: drawn }, (_, index) => (
          <span
            key={index}
            className="seat-fan-card"
            style={{ "--fan-i": index, "--fan-r": `${(index - middle) * 4.5}deg`, zIndex: index + 1 } as CSSProperties}
          />
        ))}
      </div>
    </div>
  );
}

export default function PlayerPanel({ player, isCurrentTurn = false, isNext = false, compact = false, canSelectFaceUp = false, canSelectFaceDown = false,
  selectedFaceUp = [], selectedFaceDown = [], selectedHand = [], onToggleFaceUp, onToggleFaceDown, onToggleHand, chatBubble }: Props) {
  const ownHand = player.isYou ? player.hand || [] : [];

  // Slot memory for this panel. Updated during render; reconciling the same props twice gives the same result.
  // The selection from the previous render is kept because the server state that removes the played cards
  // can arrive after the selection has already been cleared.
  const memory = useRef<{ layout: PanelLayout; selection: number[] } | null>(null);
  const previous = memory.current;
  const layout: PanelLayout = {
    faceUp: reconcileFaceUp(previous?.layout.faceUp ?? [], player.faceUp),
    faceDown: reconcileFaceDown(previous?.layout.faceDown ?? [], player.faceDownCount, previous?.selection ?? []),
  };
  memory.current = { layout, selection: selectedFaceDown };

  const faceUpOccupied = layout.faceUp.map((card) => card !== null);
  const faceUpIndexes = serverIndexes(faceUpOccupied);
  const faceDownIndexes = serverIndexes(layout.faceDown);
  const stackCount = Math.max(layout.faceUp.length, layout.faceDown.length);

  const stacks = (
    <div className="player-card-stacks" aria-label={`${player.username}'s table cards`}>
      {Array.from({ length: stackCount }, (_, slot) => {
        const card = layout.faceUp[slot] ?? null;
        const faceDownIndex = faceDownIndexes[slot] ?? -1;
        const faceUpIndex = faceUpIndexes[slot] ?? -1;
        return (
          <div className="paired-card-stack" key={slot}>
            {faceDownIndex >= 0 && (canSelectFaceDown ? (
              <button type="button" className={`playing-card face-down-card${selectedFaceDown.includes(faceDownIndex) ? " card-selected" : ""}`}
                onClick={() => onToggleFaceDown?.(faceDownIndex)} aria-pressed={selectedFaceDown.includes(faceDownIndex)}
                aria-label={`Select face-down card ${faceDownIndex + 1}`} />
            ) : <div className="playing-card face-down-card" aria-label="Face-down card" />)}
            {card && <VisibleCard card={card} index={faceUpIndex} selected={selectedFaceUp.includes(faceUpIndex)}
              onToggle={player.isYou && canSelectFaceUp ? onToggleFaceUp : undefined} />}
          </div>
        );
      })}
    </div>
  );

  return (
    <section className={`game-seat${player.isYou ? " game-seat-own" : ""}${compact ? " game-seat-compact" : ""}${isCurrentTurn ? " game-seat-active" : ""}${isNext ? " seat-next" : ""}`}
      data-seat-id={player.playerId}>
      {chatBubble && <ChatBubble key={chatBubble.ts} text={chatBubble.text} />}
      <header className="game-seat-header">
        <div className="game-seat-name" title={player.username}>{player.username}{player.isYou ? <span className="you-tag">You</span> : null}<span className="elo-badge">{Math.round(player.eloScore)}</span></div>
        {isCurrentTurn && <span className="seat-sr-only">Current turn</span>}
        {isNext && <span className="seat-sr-only">Plays next</span>}
        {!player.isYou && player.handCount > FAN_LIMIT && <div className="seat-card-counts">{player.handCount} in hand</div>}
      </header>
      {!player.isYou && <OpponentFan count={player.handCount} name={player.username} />}
      <div className="game-seat-content">
        {player.isYou && (
          <div className="own-hand-area">
            <div className="hand-label">Your hand{ownHand.length > 0 && <span>· {ownHand.length} {ownHand.length === 1 ? "card" : "cards"}</span>}</div>
            <div className="hand-row">
              <div className="playing-hand" aria-label="Your hand">
                {ownHand.map((card, index) => (
                  <VisibleCard key={`${card.suit}-${card.value}-${index}`} card={card} index={index}
                    selected={selectedHand.includes(index)} onToggle={onToggleHand} />
                ))}
                {ownHand.length === 0 && <span className="empty-hand-hint">No cards in hand</span>}
              </div>
            </div>
          </div>
        )}
        {!player.isYou && player.faceUp.length > 0 ? (
          <PeekWrap
            className="stack-peek-wrap"
            label={`Enlarge ${player.username}'s face-up cards`}
            toggleText="Enlarge"
            placement="below"
            pressToOpen={compact}
            popover={<SeatTableView player={player} isCurrentTurn={isCurrentTurn} />}
          >
            {stacks}
          </PeekWrap>
        ) : stacks}
      </div>
    </section>
  );
}
