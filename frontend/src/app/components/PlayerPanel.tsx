import { CSSProperties } from "react";
import { Card, PlayerState } from "../api/game";
import { CardFaceContent, cardRank } from "./CardFace";
import PeekWrap from "./PeekWrap";

type Props = {
  player: PlayerState;
  isCurrentTurn?: boolean;
  canSelectFaceUp?: boolean;
  canSelectFaceDown?: boolean;
  selectedFaceUp?: number[];
  selectedFaceDown?: number[];
  selectedHand?: number[];
  onToggleFaceUp?: (index: number) => void;
  onToggleFaceDown?: (index: number) => void;
  onToggleHand?: (index: number) => void;
};

/** Opponent hands are drawn as a fan of backs; larger hands are capped, the badge keeps the exact count. */
const FAN_LIMIT = 10;

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

function OpponentFan({ count, name }: { count: number; name: string }) {
  const drawn = Math.min(count, FAN_LIMIT);
  const middle = (drawn - 1) / 2;
  return (
    <div className="seat-fan-row">
      <div className="seat-fan" aria-hidden="true">
        {Array.from({ length: drawn }, (_, index) => (
          <span
            key={index}
            className="seat-fan-card"
            style={{ "--fan-i": index - middle, zIndex: index + 1 } as CSSProperties}
          />
        ))}
      </div>
      <span className="seat-fan-count" role="img" aria-label={`${name} holds ${count} ${count === 1 ? "card" : "cards"}`}>
        {count}
      </span>
    </div>
  );
}

function EnlargedFaceUp({ username, cards }: { username: string; cards: Card[] }) {
  return (
    <div className="pile-contents">
      <div className="pile-contents-title">{username}’s face-up cards</div>
      <div className="pile-contents-cards enlarged">
        {cards.map((card, index) => (
          <div key={index} className={`playing-card face-up-card pile-contents-card${card.suit === "HEARTS" || card.suit === "DIAMONDS" ? " red-card" : ""}`}
            aria-label={`${cardRank(card.value)} of ${card.suit}`}>
            <CardFaceContent card={card} />
          </div>
        ))}
      </div>
    </div>
  );
}

export default function PlayerPanel({ player, isCurrentTurn = false, canSelectFaceUp = false, canSelectFaceDown = false,
  selectedFaceUp = [], selectedFaceDown = [], selectedHand = [], onToggleFaceUp, onToggleFaceDown, onToggleHand }: Props) {
  const ownHand = player.isYou ? player.hand || [] : [];
  const stackCount = Math.max(player.faceUp.length, player.faceDownCount);
  const stacks = (
    <div className="player-card-stacks" aria-label={`${player.username}'s table cards`}>
      {Array.from({ length: stackCount }, (_, index) => {
        const faceUpCard = player.faceUp[index];
        const faceDownExists = index < player.faceDownCount;
        return (
          <div className="paired-card-stack" key={index}>
            {faceDownExists && (canSelectFaceDown ? (
              <button type="button" className={`playing-card face-down-card${selectedFaceDown.includes(index) ? " card-selected" : ""}`}
                onClick={() => onToggleFaceDown?.(index)} aria-pressed={selectedFaceDown.includes(index)}
                aria-label={`Select face-down card ${index + 1}`} />
            ) : <div className="playing-card face-down-card" aria-label="Face-down card" />)}
            {faceUpCard && <VisibleCard card={faceUpCard} index={index} selected={selectedFaceUp.includes(index)}
              onToggle={player.isYou && canSelectFaceUp ? onToggleFaceUp : undefined} />}
          </div>
        );
      })}
    </div>
  );

  return (
    <section className={`game-seat${player.isYou ? " game-seat-own" : ""}${isCurrentTurn ? " game-seat-active" : ""}`}
      data-seat-id={player.playerId}>
      <header className="game-seat-header">
        <div className="game-seat-name" title={player.username}>{player.username}{player.isYou ? <span className="you-tag">You</span> : null}<span className="elo-badge">{Math.round(player.eloScore)}</span></div>
        {isCurrentTurn && <span className="seat-turn">Playing</span>}
        {!player.isYou && <div className="seat-card-counts">{player.handCount} in hand <span>·</span> {player.faceDownCount} hidden</div>}
      </header>
      {!player.isYou && <OpponentFan count={player.handCount} name={player.username} />}
      <div className="game-seat-content">
        {!player.isYou && player.faceUp.length > 0 ? (
          <PeekWrap
            className="stack-peek-wrap"
            label={`Enlarge ${player.username}'s face-up cards`}
            toggleText="Enlarge"
            placement="below"
            popover={<EnlargedFaceUp username={player.username} cards={player.faceUp} />}
          >
            {stacks}
          </PeekWrap>
        ) : stacks}
        {player.isYou && (
          <div className="own-hand-area">
            <div className="hand-label">Your hand <span>{ownHand.length}</span></div>
            <div className="playing-hand" aria-label="Your hand">
              {ownHand.map((card, index) => (
                <VisibleCard key={`${card.suit}-${card.value}-${index}`} card={card} index={index}
                  selected={selectedHand.includes(index)} onToggle={onToggleHand} />
              ))}
              {ownHand.length === 0 && <span className="empty-hand-hint">No cards in hand</span>}
            </div>
          </div>
        )}
      </div>
    </section>
  );
}
