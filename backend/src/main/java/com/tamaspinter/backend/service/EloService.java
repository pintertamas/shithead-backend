package com.tamaspinter.backend.service;

import java.util.HashMap;
import java.util.Map;

public class EloService {
    private static final double K = 32.0;

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
}
