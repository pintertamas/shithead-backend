package com.tamaspinter.backend.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EloServiceTenPlayersTest {

    private static List<String> tenIds() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ids.add("p" + i);
        }
        return ids;
    }

    @Test
    void shitheadScores_sumToHalfThePlayerCount() {
        // Given: 10 players, p0 is the shithead
        // When
        Map<String, Double> scores = EloService.shitheadScores(tenIds(), "p0");

        // Then: the shithead scores 0 and the nine others share the same score, totalling n / 2 = 5
        assertEquals(0.0, scores.get("p0"), 0.0001);
        assertEquals(5.0 / 9.0, scores.get("p5"), 0.0001);
        assertEquals(5.0, scores.values().stream().mapToDouble(Double::doubleValue).sum(), 0.0001);
    }

    @Test
    void tenPlayers_equalRatings_ratingChangesSumToZero() {
        // Given: ten players all at 1000, the shithead is p0
        Map<String, Double> current = new HashMap<>();
        tenIds().forEach(id -> current.put(id, 1000.0));
        Map<String, Double> scores = EloService.shitheadScores(tenIds(), "p0");

        // When
        Map<String, Double> updated = EloService.updateRatings(current, scores);

        // Then: nothing is created or destroyed in total
        double total = updated.values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(10000.0, total, 0.0001);
    }

    @Test
    void tenPlayers_uneven_ratingChangesSumToZero() {
        // Given: ten players with spread ratings, p7 is the shithead
        Map<String, Double> current = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            current.put("p" + i, 800.0 + i * 60.0);
        }
        Map<String, Double> scores = EloService.shitheadScores(tenIds(), "p7");

        // When
        Map<String, EloService.EloChange> changes = EloService.calculateChanges(current, scores);

        // Then
        double delta = changes.values().stream().mapToDouble(c -> c.after() - c.before()).sum();
        assertEquals(0.0, delta, 0.0001);
    }

    @Test
    void twoPlayers_shitheadScores_matchWinnerAndLoser() {
        // Given / When: two players, the non-shithead wins outright
        Map<String, Double> scores = EloService.shitheadScores(List.of("a", "b"), "b");

        // Then
        assertEquals(1.0, scores.get("a"), 0.0001);
        assertEquals(0.0, scores.get("b"), 0.0001);
    }
}
