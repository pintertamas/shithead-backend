package com.tamaspinter.backend.config;

import com.tamaspinter.backend.entity.EloChangeEntity;
import com.tamaspinter.backend.model.UserProfile;
import com.tamaspinter.backend.model.api.LeaderboardEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SessionLeaderboardEntriesTest {

    private static UserProfile profile(String id, String name, double elo) {
        return UserProfile.builder().userId(id).username(name).eloScore(elo).build();
    }

    @Test
    void testRecordedChange_fillsEloBeforeAndAfter() {
        // Given — alice's game change was 1000 -> 1016 and her current rating is 1016
        Map<String, UserProfile> profiles = Map.of("alice", profile("alice", "Alice", 1016.0));
        Map<String, EloChangeEntity> changes = Map.of(
                "alice", EloChangeEntity.builder().before(1000.0).after(1016.0).build());

        // When
        List<LeaderboardEntry> entries = GameFunctionConfig.sessionEntries(List.of("alice"), profiles, changes);

        // Then — current rating stays in eloScore; the recorded change is exposed
        assertEquals(1, entries.size());
        assertEquals(1016.0, entries.get(0).eloScore(), 0.0001);
        assertEquals(1000.0, entries.get(0).eloBefore(), 0.0001);
        assertEquals(1016.0, entries.get(0).eloAfter(), 0.0001);
    }

    @Test
    void testNoRecordedChanges_leavesEloBeforeNull() {
        // Given — a finished game from before changes were recorded (eloChanges absent)
        Map<String, UserProfile> profiles = Map.of("bob", profile("bob", "Bob", 985.0));

        // When
        List<LeaderboardEntry> entries = GameFunctionConfig.sessionEntries(List.of("bob"), profiles, null);

        // Then
        assertEquals(985.0, entries.get(0).eloScore(), 0.0001);
        assertNull(entries.get(0).eloBefore());
        assertNull(entries.get(0).eloAfter());
    }

    @Test
    void testPlayerMissingFromChanges_getsNullEloBefore() {
        // Given — alice has a recorded change, bob does not
        Map<String, UserProfile> profiles = Map.of(
                "alice", profile("alice", "Alice", 1016.0),
                "bob", profile("bob", "Bob", 969.0));
        Map<String, EloChangeEntity> changes = Map.of(
                "alice", EloChangeEntity.builder().before(1000.0).after(1016.0).build());

        // When
        List<LeaderboardEntry> entries = GameFunctionConfig.sessionEntries(List.of("alice", "bob"), profiles, changes);

        // Then — seat order is kept; only the player with a change has eloBefore
        assertEquals("alice", entries.get(0).userId());
        assertEquals(1000.0, entries.get(0).eloBefore(), 0.0001);
        assertEquals("bob", entries.get(1).userId());
        assertNull(entries.get(1).eloBefore());
    }

    @Test
    void testUnknownProfile_fallsBackToUnknownWithoutChange() {
        // Given — the player has no profile row
        // When
        List<LeaderboardEntry> entries = GameFunctionConfig.sessionEntries(List.of("ghost"), Map.of(), Map.of());

        // Then
        assertEquals("Unknown", entries.get(0).username());
        assertEquals(0.0, entries.get(0).eloScore(), 0.0001);
        assertNull(entries.get(0).eloBefore());
    }
}
