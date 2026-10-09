package com.tamaspinter.backend.service;

import com.tamaspinter.backend.service.EloService.EloChange;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EloServiceChangesTest {

    @Test
    void testEvenMatch_recordsBeforeAndAfterForBothPlayers() {
        // Given — two players at 1000, the winner scores 1 and the loser 0
        Map<String, Double> current = Map.of("winner", 1000.0, "loser", 1000.0);
        Map<String, Double> scores = Map.of("winner", 1.0, "loser", 0.0);

        // When
        Map<String, EloChange> changes = EloService.calculateChanges(current, scores);

        // Then — K=32, delta is +/-16 and before is the rating the update started from
        assertEquals(1000.0, changes.get("winner").before(), 0.0001);
        assertEquals(1016.0, changes.get("winner").after(), 0.0001);
        assertEquals(1000.0, changes.get("loser").before(), 0.0001);
        assertEquals(984.0, changes.get("loser").after(), 0.0001);
    }

    @Test
    void testChanges_matchUpdateRatingsResult() {
        // Given — three players with uneven ratings
        Map<String, Double> current = Map.of("a", 1200.0, "b", 1000.0, "c", 800.0);
        Map<String, Double> scores = Map.of("a", 1.0, "b", 1.0, "c", 0.0);

        // When
        Map<String, EloChange> changes = EloService.calculateChanges(current, scores);
        Map<String, Double> updated = EloService.updateRatings(current, scores);

        // Then — every after value is the rating the regular update produces
        assertEquals(current.keySet(), changes.keySet());
        for (String id : current.keySet()) {
            assertEquals(updated.get(id), changes.get(id).after(), 0.0001);
            assertEquals(current.get(id), changes.get(id).before(), 0.0001);
        }
        assertTrue(changes.get("c").after() < changes.get("c").before());
    }
}
