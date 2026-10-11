package com.tamaspinter.backend.bot;

import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.Player;
import com.tamaspinter.backend.model.Suit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a bot can see at the table right now: its own hand and face-up cards, how many face-down cards it has,
 * the discard pile, the draw pile size, every opponent's face-up cards and card counts, and the game rules.
 * Opponents' hands, all face-down cards and the deck order are left out on purpose.
 *
 * <p>A card-counting bot also gets what it remembers from public moves ({@link CardMemory}): the values each
 * opponent is known to hold, and per value how many cards it has not seen anywhere (they are in a deck, a
 * face-down pile or an opponent's unknown hand cards). Other bots get an empty memory.
 */
public record BotView(
        String playerId,
        List<Card> hand,
        List<Card> faceUp,
        int faceDownCount,
        List<Card> pile,
        int deckCount,
        List<OpponentView> opponents,
        GameConfig config,
        Map<String, List<Integer>> knownOpponentHands,
        List<Integer> unseenByValue
) {

    /** An opponent as seen from the bot's seat, in seat order starting after the bot. */
    public record OpponentView(String playerId, int handCount, List<Card> faceUp, int faceDownCount, boolean out) {
    }

    /** Values this opponent is known to hold in hand; empty when unknown or for bots without memory. */
    public List<Integer> knownHand(String opponentId) {
        return knownOpponentHands.getOrDefault(opponentId, List.of());
    }

    /** Cards of this value the bot has not seen anywhere; 0 for bots without memory. */
    public int unseen(int value) {
        return value >= 0 && value < unseenByValue.size() ? unseenByValue.get(value) : 0;
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
                session.getConfig(),
                knownOpponentHands(session, self),
                unseenByValue(session, self));
    }

    private static CardMemory memoryFor(GameSession session, Player self) {
        boolean counts = self.isBot() && self.getBotType().countsCards();
        return counts && session.getObserver() instanceof CardMemory memory ? memory : null;
    }

    private static Map<String, List<Integer>> knownOpponentHands(GameSession session, Player self) {
        CardMemory memory = memoryFor(session, self);
        Map<String, List<Integer>> known = new HashMap<>();
        if (memory == null) {
            return Map.of();
        }
        session.getPlayers().stream()
                .filter(player -> !player.getPlayerId().equals(self.getPlayerId()))
                .forEach(player -> {
                    List<Integer> values = memory.knownHand(player.getPlayerId());
                    if (!values.isEmpty()) {
                        known.put(player.getPlayerId(), values.subList(0, Math.min(values.size(), player.getHand().size())));
                    }
                });
        return Map.copyOf(known);
    }

    /**
     * Per value: every copy in the decks, minus burned ones, the pile, all face-up cards, the bot's own hand and the
     * cards it knows opponents hold. Never negative.
     */
    private static List<Integer> unseenByValue(GameSession session, Player self) {
        CardMemory memory = memoryFor(session, self);
        if (memory == null) {
            return List.of();
        }
        int[] unseen = new int[CardMemory.MAX_VALUE + 1];
        int copies = session.getConfig().getDecksCount() * Suit.values().length;
        for (int value = CardMemory.MIN_VALUE; value <= CardMemory.MAX_VALUE; value++) {
            unseen[value] = copies - memory.burnedCount(value);
        }
        List<Integer> seen = new ArrayList<>();
        session.getDiscardPile().forEach(card -> seen.add(card.getValue()));
        self.getHand().forEach(card -> seen.add(card.getValue()));
        for (Player player : session.getPlayers()) {
            player.getFaceUp().forEach(card -> seen.add(card.getValue()));
            if (!player.getPlayerId().equals(self.getPlayerId())) {
                memory.knownHand(player.getPlayerId()).stream().limit(player.getHand().size()).forEach(seen::add);
            }
        }
        for (int value : seen) {
            unseen[value]--;
        }
        List<Integer> counts = new ArrayList<>();
        for (int count : unseen) {
            counts.add(Math.max(0, count));
        }
        return List.copyOf(counts);
    }
}
