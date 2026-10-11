package com.tamaspinter.backend.bot;

import com.tamaspinter.backend.entity.BotMemoryEntity;
import com.tamaspinter.backend.game.PublicMoveObserver;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What a card-counting bot remembers, built only from public moves (see {@link PublicMoveObserver}): which card
 * values each player is known to hold because everyone saw them go into that hand, and how many cards of each value
 * were burned. Everything else a bot needs (the pile, face-up cards, card counts) is on the table anyway.
 * One memory serves the whole game because every seat sees the same public moves. Values only: suits never matter
 * to the rules.
 */
public class CardMemory implements PublicMoveObserver {
    /** Card values run from 2 to 14 (ace). */
    public static final int MIN_VALUE = 2;
    public static final int MAX_VALUE = 14;

    private final Map<String, List<Integer>> knownHands = new HashMap<>();
    private final int[] burned = new int[MAX_VALUE + 1];

    @Override
    public void cardsPlayed(Player player, List<Card> cards) {
        List<Integer> known = knownHands.get(player.getPlayerId());
        if (known == null) {
            return;
        }
        forget(known, cards);
        // The hand size is public: a known list can never be longer than the hand.
        while (known.size() > player.getHand().size()) {
            known.remove(0);
        }
        if (known.isEmpty()) {
            knownHands.remove(player.getPlayerId());
        }
    }

    @Override
    public void cardsPickedUp(Player player, List<Card> cards) {
        remember(player, cards);
    }

    @Override
    public void pileBurned(List<Card> cards) {
        for (Card card : cards) {
            burned[card.getValue()]++;
        }
    }

    @Override
    public void faceUpTakenIntoHand(Player player, List<Card> cards) {
        remember(player, cards);
    }

    /** A card swapped back face up is on the table again; forgetting one copy never claims a card that left. */
    @Override
    public void handCardsPutFaceUp(Player player, List<Card> cards) {
        List<Integer> known = knownHands.get(player.getPlayerId());
        if (known == null) {
            return;
        }
        forget(known, cards);
        if (known.isEmpty()) {
            knownHands.remove(player.getPlayerId());
        }
    }

    private static void forget(List<Integer> known, List<Card> cards) {
        for (Card card : cards) {
            int index = known.indexOf(card.getValue());
            if (index >= 0) {
                known.remove(index);
            }
        }
    }

    private void remember(Player player, List<Card> cards) {
        List<Integer> known = knownHands.computeIfAbsent(player.getPlayerId(), id -> new ArrayList<>());
        cards.forEach(card -> known.add(card.getValue()));
    }

    /** Values the player is known to hold in hand, possibly fewer than the hand has. */
    public List<Integer> knownHand(String playerId) {
        return List.copyOf(knownHands.getOrDefault(playerId, List.of()));
    }

    /** Cards of this value removed from the game by burns. */
    public int burnedCount(int value) {
        return value < MIN_VALUE || value > MAX_VALUE ? 0 : burned[value];
    }

    /** Compact stored form: known values as "3,3,10" per player id, burn counts for values 2..14. */
    public BotMemoryEntity toEntity() {
        Map<String, String> hands = new HashMap<>();
        knownHands.forEach((id, values) -> hands.put(id, values.stream().map(String::valueOf).collect(Collectors.joining(","))));
        String burnedCounts = Arrays.stream(burned, MIN_VALUE, MAX_VALUE + 1).mapToObj(String::valueOf)
                .collect(Collectors.joining(","));
        return BotMemoryEntity.builder().knownHands(hands).burned(burnedCounts).build();
    }

    /** Restores a stored memory; a missing or malformed one starts empty rather than failing the move. */
    public static CardMemory fromEntity(BotMemoryEntity entity) {
        CardMemory memory = new CardMemory();
        if (entity == null) {
            return memory;
        }
        if (entity.getKnownHands() != null) {
            entity.getKnownHands().forEach((id, values) -> {
                List<Integer> parsed = parseInts(values);
                if (!parsed.isEmpty()) {
                    memory.knownHands.put(id, new ArrayList<>(parsed));
                }
            });
        }
        List<Integer> counts = parseInts(entity.getBurned());
        for (int i = 0; i < counts.size() && MIN_VALUE + i <= MAX_VALUE; i++) {
            memory.burned[MIN_VALUE + i] = counts.get(i);
        }
        return memory;
    }

    private static List<Integer> parseInts(String text) {
        List<Integer> values = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return values;
        }
        for (String part : text.split(",")) {
            try {
                values.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException e) {
                return new ArrayList<>();
            }
        }
        return values;
    }
}
