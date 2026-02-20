package com.tamaspinter.backend.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EloServiceTest {

    // =========================================================================
    // Two players — even rating
    // =========================================================================

    @Test
    void testEvenRating_winnerGains16_loserLoses16() {
        // Given — both players at 1000; E = 0.5 for each
        Map<String, Double> current = Map.of("winner", 1000.0, "loser", 1000.0);
        Map<String, Double> scores  = Map.of("winner", 1.0,    "loser", 0.0);

        // When
        Map<String, Double> updated = EloService.updateRatings(current, scores);

        // Then — K=32, S-E = ±0.5 → delta = 16
        assertEquals(1016.0, updated.get("winner"), 0.001);
        assertEquals(984.0,  updated.get("loser"),  0.001);
    }

    // =========================================================================
    // Two players — uneven rating
    // =========================================================================

    @Test
    void testUnevenRating_highRatedWinnerGainsLess() {
        // Given — heavy favourite (1200) beats underdog (800)
        //   E_winner = 1/(1+10^(-1)) = 10/11 ≈ 0.909
        //   delta    = 32 * (1 - 10/11) = 32/11 ≈ 2.91
        Map<String, Double> current = Map.of("winner", 1200.0, "loser", 800.0);
        Map<String, Double> scores  = Map.of("winner", 1.0,    "loser", 0.0);

        // When
        Map<String, Double> updated = EloService.updateRatings(current, scores);

        // Then — favourite gains little; underdog loses little
        assertEquals(1200.0 + 32.0 / 11.0, updated.get("winner"), 0.01);
        assertEquals(800.0  - 32.0 / 11.0, updated.get("loser"),  0.01);
        assertTrue(updated.get("winner") - 1200.0 < 16.0); // gains less than even-match delta
    }

    // =========================================================================
    // Three players — shithead scores 0, others score 1
    // =========================================================================

    @Test
    void testMultiplePlayers_shitheadRatingDecreases_winnersIncrease() {
        // Given — 3 equal players at 1000; only "shithead" scores 0.0
        Map<String, Double> current = Map.of("alice", 1000.0, "bob", 1000.0, "shithead", 1000.0);
        Map<String, Double> scores  = Map.of("alice", 1.0,    "bob", 1.0,    "shithead", 0.0);

        // When
        Map<String, Double> updated = EloService.updateRatings(current, scores);

        // Then — all opponents equal → E=0.5 for every player → delta = 32*(S-0.5)
        assertEquals(1016.0, updated.get("alice"),    0.001);
        assertEquals(1016.0, updated.get("bob"),      0.001);
        assertEquals(984.0,  updated.get("shithead"), 0.001);
    }
}
