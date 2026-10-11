package com.tamaspinter.backend.bot;

import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * What a bot can see at the table right now: its own hand and face-up cards, how many face-down cards it has,
 * the discard pile, the draw pile size, every opponent's face-up cards and card counts, and the game rules.
 * Opponents' hands, all face-down cards and the deck order are left out on purpose.
 */
public record BotView(
        String playerId,
        List<Card> hand,
        List<Card> faceUp,
        int faceDownCount,
        List<Card> pile,
        int deckCount,
        List<OpponentView> opponents,
        GameConfig config
) {

    /** An opponent as seen from the bot's seat, in seat order starting after the bot. */
    public record OpponentView(String playerId, int handCount, List<Card> faceUp, int faceDownCount, boolean out) {
    }

    /** The public view of the session from the seat of the given player. */
    public static BotView of(GameSession session, Player self) {
        List<Player> players = session.getPlayers();
        int seat = players.indexOf(self);
        List<OpponentView> opponents = new ArrayList<>();
        for (int offset = 1; offset < players.size(); offset++) {
            Player other = players.get((seat + offset) % players.size());
            opponents.add(new OpponentView(other.getPlayerId(), other.getHand().size(),
                    List.copyOf(other.getFaceUp()), other.getFaceDown().size(), other.isOut()));
        }
        return new BotView(
                self.getPlayerId(),
                List.copyOf(self.getHand()),
                List.copyOf(self.getFaceUp()),
                self.getFaceDown().size(),
                List.copyOf(session.getDiscardPile()),
                session.getDeck() == null ? 0 : session.getDeck().getCards().size(),
                List.copyOf(opponents),
                session.getConfig());
    }
}
