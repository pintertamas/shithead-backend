package com.tamaspinter.backend.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class EloService {
    private static final double K = 32.0;

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
     * Pairwise-consistent scores for a game that ends with one shithead: the shithead scores 0 against
     * everyone, and every other player beats the shithead and ties the rest (1 point for a win, 0.5 for a tie).
     * Each score is divided by the number of opponents, so the scores sum to n / 2, the same total as the
     * expected scores, which keeps the rating changes zero-sum for any number of players.
     */
    public static Map<String, Double> shitheadScores(Collection<String> playerIds, String shitheadId) {
        int count = playerIds.size();
        Map<String, Double> scores = new HashMap<>();
        if (count < 2) {
            playerIds.forEach(id -> scores.put(id, 0.0));
            return scores;
        }
        double winnerScore = (1.0 + 0.5 * (count - 2)) / (count - 1);
        for (String id : playerIds) {
            scores.put(id, id.equals(shitheadId) ? 0.0 : winnerScore);
        }
        return scores;
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
}
