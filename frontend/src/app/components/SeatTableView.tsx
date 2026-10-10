import { PlayerState } from "../api/game";
import { CardFaceContent, cardRank, isRedSuit } from "./CardFace";
import "../styles/piles.css";

/** The hand is drawn as at most this many backs; the label always shows the exact count. */
const FAN_LIMIT = 13;

/**
 * A player's seat as it is on the table: name and Elo, the face-down cards (backs, exact count) with the
 * face-up card of each pair laid over them, and the hand as a fan of backs with the exact hand count.
 * Used by the opponent popovers on desktop and tablet, and by the phone seat dialog.
 */
export default function SeatTableView({ player, isCurrentTurn = false }: { player: PlayerState; isCurrentTurn?: boolean }) {
  const faceUp = player.faceUp;
  const backs = player.faceDownCount;
  const slots = Math.max(backs, faceUp.length);
  const fanCount = Math.min(player.handCount, FAN_LIMIT);
  const faceDownLabel = `${backs} face-down ${backs === 1 ? "card" : "cards"}`;
  const faceUpLabel = `${faceUp.length} face-up ${faceUp.length === 1 ? "card" : "cards"}`;

  return (
    <div className="seat-table-view" role="group" aria-label={`${player.username}'s cards`}>
      <div className="seat-table-head">
        <strong>{player.username}</strong>
        <span className="elo-badge">{Math.round(player.eloScore)}</span>
        {isCurrentTurn && <span className="seat-turn">Playing</span>}
      </div>
      <div className="seat-table-counts">{player.handCount} in hand · {backs} face-down · {faceUp.length} face-up</div>

      <section className="seat-table-section" aria-label="On the table">
        <div className="seat-table-label">On the table</div>
        {slots === 0 ? (
          <p className="seat-table-empty">No cards on the table.</p>
        ) : (
          <div className="seat-table-stacks" aria-label={`${faceDownLabel}, ${faceUpLabel}`}>
            {Array.from({ length: slots }, (_, index) => {
              const card = faceUp[index];
              return (
                <div className="seat-table-stack" key={index}>
                  {index < backs && (
                    <div className="playing-card face-down-card seat-table-back" aria-label="Face-down card" />
                  )}
                  {card && (
                    <div
                      className={`playing-card face-up-card${isRedSuit(card.suit) ? " red-card" : ""}`}
                      aria-label={`${cardRank(card.value)} of ${card.suit}, face-up`}
                    >
                      <CardFaceContent card={card} />
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </section>

      <section className="seat-table-section" aria-label="Hand">
        <div className="seat-table-label">Hand · {player.handCount} {player.handCount === 1 ? "card" : "cards"}</div>
        {player.handCount === 0 ? (
          <p className="seat-table-empty">No cards in hand.</p>
        ) : (
          <div className="seat-table-fan" aria-label={`${player.handCount} ${player.handCount === 1 ? "card" : "cards"} in hand`}>
            {Array.from({ length: fanCount }, (_, index) => (
              <span key={index} className="seat-table-fan-card" aria-hidden="true" />
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
