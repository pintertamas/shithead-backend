package com.tamaspinter.backend.service;

import com.tamaspinter.backend.bot.BotType;
import com.tamaspinter.backend.model.Player;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class EloService {
    private static final double K = 32.0;
    /** Users-table id prefix of a bot type's rating row. Cognito subs are UUIDs, so no account can have this id. */
    public static final String BOT_TYPE_ID_PREFIX = "bot-type-";

    /** A player's rating before and after one Elo update. */
    public record EloChange(double before, double after) {
    }

    public static Map<String, Double> updateRatings(Map<String, Double> current, Map<String, Double> scores) {
        Map<String, Double> updated = new HashMap<>();
        for (Map.Entry<String, Double> entry : current.entrySet()) {
            String id = entry.getKey();
            double currentRating = entry.getValue();
            double score = scores.getOrDefault(id, 0.0);
            double expectedScore = 0.0;
            for (Map.Entry<String, Double> opponentEntry : current.entrySet()) {
                String opponentId = opponentEntry.getKey();
                if (!opponentId.equals(id)) {
                    double opponentRating = opponentEntry.getValue();
                    expectedScore += 1 / (1 + Math.pow(10, (opponentRating - currentRating) / 400));
                }
            }
            expectedScore /= (current.size() - 1);
            double newRating = currentRating + K * (score - expectedScore);
            updated.put(id, newRating);
        }
        return updated;
    }

    /**
     * Runs {@link #updateRatings} and pairs every player's old and new rating.
     */
    public static Map<String, EloChange> calculateChanges(Map<String, Double> current, Map<String, Double> scores) {
        Map<String, Double> updated = updateRatings(current, scores);
        Map<String, EloChange> changes = new HashMap<>();
        updated.forEach((id, after) -> changes.put(id, new EloChange(current.get(id), after)));
        return changes;
    }

    public static String botTypeId(BotType type) {
        return BOT_TYPE_ID_PREFIX + type.name();
    }

    public static boolean isBotTypeId(String id) {
        return id != null && id.startsWith(BOT_TYPE_ID_PREFIX);
    }

    /**
     * The rated participant a seat stands for: a human is itself, every bot of one type is that one type. This is
     * the anti-inflation rule: however many instances of a type sit at the table, the type is a single participant,
     * so it gets one update with the same K as a single player and humans are rated against one opponent per type.
     */
    public static String participantId(Player player) {
        return player.isBot() ? botTypeId(player.getBotType()) : player.getPlayerId();
    }

    /**
     * Rates a finished game that has at least one human. Participants are the humans and one virtual participant
     * per bot type present. The seat of {@code shitheadId} loses (score 0), every other participant scores 1, so a
     * bot type loses when one of its instances is the shithead (its worst-placed instance decides) and wins
     * otherwise. {@code ratings} maps participant ids to their current rating; participants missing from it are
     * left out. Returns an empty map when nothing can be rated: no human, fewer than two rated participants, or the
     * shithead is not among them (nobody would lose).
     */
    public static Map<String, EloChange> rateGame(Collection<Player> players, String shitheadId,
            Map<String, Double> ratings) {
        String loser = players.stream().filter(player -> player.getPlayerId().equals(shitheadId))
                .map(EloService::participantId).findFirst().orElse(null);
        Map<String, Double> current = new HashMap<>();
        Map<String, Double> scores = new HashMap<>();
        for (Player player : players) {
            String id = participantId(player);
            if (ratings.containsKey(id)) {
                current.put(id, ratings.get(id));
                scores.put(id, id.equals(loser) ? 0.0 : 1.0);
            }
        }
        boolean anyHuman = players.stream().anyMatch(player -> !player.isBot());
        return anyHuman && current.size() >= 2 && current.containsKey(loser)
                ? calculateChanges(current, scores)
                : Map.of();
    }
}
