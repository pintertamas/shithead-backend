import { Card, PlayerState } from "../api/game";

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

function cardLabel(value: number) {
  return ({ 11: "J", 12: "Q", 13: "K", 14: "A" } as Record<number, string>)[value] || String(value);
}

function suitSymbol(suit: string) {
  return ({ CLUBS: "♣", DIAMONDS: "♦", HEARTS: "♥", SPADES: "♠" } as Record<string, string>)[suit] || suit;
}

function VisibleCard({ card, index, selected, onToggle }: {
  card: Card;
  index: number;
  selected: boolean;
  onToggle?: (index: number) => void;
}) {
  const content = <>
    <span className="playing-card-corner">{cardLabel(card.value)}<br />{suitSymbol(card.suit)}</span>
    <span className="playing-card-corner-opposite" aria-hidden="true">{cardLabel(card.value)}<br />{suitSymbol(card.suit)}</span>
    <span className="playing-card-center">{suitSymbol(card.suit)}</span>
  </>;
  const className = `playing-card face-up-card${selected ? " card-selected" : ""}${onToggle ? " card-selectable" : ""}`;
  return onToggle ? (
    <button type="button" className={className} onClick={() => onToggle(index)} aria-pressed={selected}
      aria-label={`${cardLabel(card.value)} of ${card.suit}${selected ? ", selected" : ""}`}>
      {content}
    </button>
  ) : <div className={className} aria-label={`${cardLabel(card.value)} of ${card.suit}`}>{content}</div>;
}

export default function PlayerPanel({ player, isCurrentTurn = false, canSelectFaceUp = false, canSelectFaceDown = false,
  selectedFaceUp = [], selectedFaceDown = [], selectedHand = [], onToggleFaceUp, onToggleFaceDown, onToggleHand }: Props) {
  const ownHand = player.isYou ? player.hand || [] : [];
  const stackCount = Math.max(player.faceUp.length, player.faceDownCount);
  return (
    <section className={`game-seat${player.isYou ? " game-seat-own" : ""}${isCurrentTurn ? " game-seat-active" : ""}`}>
      <header className="game-seat-header">
        <div className="game-seat-name" title={player.username}>{player.username}{player.isYou ? <span className="you-tag">You</span> : null}<span className="elo-badge">{Math.round(player.eloScore)}</span></div>
        {isCurrentTurn && <span className="seat-turn">Playing</span>}
        {!player.isYou && <div className="seat-card-counts">{player.handCount} in hand <span>·</span> {player.faceDownCount} hidden</div>}
      </header>
      <div className="game-seat-content">
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
