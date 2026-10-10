package com.tamaspinter.backend.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamaspinter.backend.entity.EloChangeEntity;
import com.tamaspinter.backend.model.UserProfile;
import com.tamaspinter.backend.model.api.LeaderboardEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionLeaderboardEntriesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        List<LeaderboardEntry> entries = GameFunctionConfig.sessionEntries(List.of("alice"), profiles, changes, false, null);

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
        List<LeaderboardEntry> entries = GameFunctionConfig.sessionEntries(List.of("bob"), profiles, null, false, null);

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
        List<LeaderboardEntry> entries = GameFunctionConfig.sessionEntries(List.of("alice", "bob"), profiles, changes, false, null);

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
        List<LeaderboardEntry> entries = GameFunctionConfig.sessionEntries(List.of("ghost"), Map.of(), Map.of(), false, null);

        // Then
        assertEquals("Unknown", entries.get(0).username());
        assertEquals(0.0, entries.get(0).eloScore(), 0.0001);
        assertNull(entries.get(0).eloBefore());
    }

    @Test
    void sessionEntries_finishedGame_marksOnlyShitheadSeat() {
        // Given — a finished game where bob is the shithead, seated between alice and carol
        Map<String, UserProfile> profiles = Map.of(
                "alice", profile("alice", "Alice", 1000.0),
                "bob", profile("bob", "Bob", 1000.0),
                "carol", profile("carol", "Carol", 1000.0));

        // When
        List<LeaderboardEntry> entries = GameFunctionConfig.sessionEntries(
                List.of("alice", "bob", "carol"), profiles, null, true, "bob");

        // Then — only bob's seat carries the shithead mark, and seat order is kept
        assertEquals(3, entries.size());
        assertEquals("alice", entries.get(0).userId());
        assertFalse(entries.get(0).shithead());
        assertEquals("bob", entries.get(1).userId());
        assertTrue(entries.get(1).shithead());
        assertEquals("carol", entries.get(2).userId());
        assertFalse(entries.get(2).shithead());
    }

    @Test
    void sessionEntries_unfinishedGame_marksNobody() {
        // Given — a game still in progress that already names a shithead id
        Map<String, UserProfile> profiles = Map.of(
                "alice", profile("alice", "Alice", 1000.0),
                "bob", profile("bob", "Bob", 1000.0));

        // When
        List<LeaderboardEntry> entries = GameFunctionConfig.sessionEntries(List.of("alice", "bob"), profiles, null, false, "bob");

        // Then — nobody is marked until the game is finished
        assertFalse(entries.get(0).shithead());
        assertFalse(entries.get(1).shithead());
    }

    @Test
    void sessionEntries_nullShitheadId_marksNobody() {
        // Given — a finished game whose shithead id is missing
        Map<String, UserProfile> profiles = Map.of(
                "alice", profile("alice", "Alice", 1000.0),
                "bob", profile("bob", "Bob", 1000.0));

        // When
        List<LeaderboardEntry> entries = GameFunctionConfig.sessionEntries(List.of("alice", "bob"), profiles, null, true, null);

        // Then
        assertFalse(entries.get(0).shithead());
        assertFalse(entries.get(1).shithead());
    }

    @Test
    void sessionEntries_finishedGame_serializesShitheadAsBooleanKey() throws JsonProcessingException {
        // Given — a finished game where alice is the shithead
        Map<String, UserProfile> profiles = Map.of(
                "alice", profile("alice", "Alice", 1000.0),
                "bob", profile("bob", "Bob", 1000.0));
        List<LeaderboardEntry> entries = GameFunctionConfig.sessionEntries(List.of("alice", "bob"), profiles, null, true, "alice");

        // When
        JsonNode rows = MAPPER.readTree(MAPPER.writeValueAsString(entries));

        // Then — every session row has a "shithead" boolean, true only for alice
        assertTrue(rows.get(0).get("shithead").asBoolean());
        assertFalse(rows.get(1).get("shithead").asBoolean());
    }

    @Test
    void leaderboardEntry_unmarkedRow_omitsShitheadKey() throws JsonProcessingException {
        // Given — a global leaderboard row, which never carries the shithead mark
        LeaderboardEntry row = LeaderboardEntry.builder().userId("carol").username("Carol").eloScore(1010.0).build();

        // When
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(row));

        // Then — the global leaderboard JSON is unchanged: no shithead key
        assertFalse(json.has("shithead"));
    }
}
