package com.tamaspinter.backend.mapper;

import com.tamaspinter.backend.entity.EloChangeEntity;
import com.tamaspinter.backend.entity.GameSessionEntity;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionMapperEloChangesTest {

    private static final TableSchema<GameSessionEntity> SCHEMA = TableSchema.fromBean(GameSessionEntity.class);

    @Test
    void testEloChanges_surviveDynamoDbRoundTrip() {
        // Given — a finished game with recorded Elo changes
        GameSessionEntity entity = GameSessionEntity.builder()
                .sessionId("game-1")
                .finished(true)
                .eloUpdated(true)
                .eloChanges(Map.of(
                        "alice", EloChangeEntity.builder().before(1000.0).after(1016.0).build(),
                        "bob", EloChangeEntity.builder().before(985.0).after(969.0).build()))
                .build();

        // When — written to a DynamoDB item and read back
        Map<String, AttributeValue> item = SCHEMA.itemToMap(entity, true);
        GameSessionEntity loaded = SCHEMA.mapToItem(item);

        // Then — both players' before/after values and the flag are intact
        assertTrue(loaded.isEloUpdated());
        assertEquals(2, loaded.getEloChanges().size());
        assertEquals(1000.0, loaded.getEloChanges().get("alice").getBefore(), 0.0001);
        assertEquals(1016.0, loaded.getEloChanges().get("alice").getAfter(), 0.0001);
        assertEquals(985.0, loaded.getEloChanges().get("bob").getBefore(), 0.0001);
        assertEquals(969.0, loaded.getEloChanges().get("bob").getAfter(), 0.0001);
    }

    @Test
    void testOldItemWithoutEloChanges_loadsAsNull() {
        // Given — an item written before eloChanges existed (no eloChanges attribute)
        GameSessionEntity entity = GameSessionEntity.builder()
                .sessionId("old-game")
                .finished(true)
                .eloUpdated(true)
                .build();
        Map<String, AttributeValue> item = new HashMap<>(SCHEMA.itemToMap(entity, true));
        item.remove("eloChanges");

        // When
        GameSessionEntity loaded = SCHEMA.mapToItem(item);

        // Then — loads without error, no changes recorded
        assertNull(loaded.getEloChanges());
        assertTrue(loaded.isEloUpdated());
    }

    @Test
    void testCarryEloState_copiesFlagAndChangesToRebuiltEntity() {
        // Given — a stored finished game and a copy rebuilt from the domain object (which has no Elo state)
        Map<String, EloChangeEntity> changes = Map.of("alice", EloChangeEntity.builder().before(1000.0).after(1016.0).build());
        GameSessionEntity stored = GameSessionEntity.builder()
                .sessionId("game-2")
                .eloUpdated(true)
                .eloChanges(changes)
                .build();
        GameSessionEntity rebuilt = GameSessionEntity.builder().sessionId("game-2").build();

        // When
        SessionMapper.carryEloState(stored, rebuilt);

        // Then
        assertTrue(rebuilt.isEloUpdated());
        assertEquals(changes, rebuilt.getEloChanges());
    }

    @Test
    void testCarryEloState_leavesRebuiltEntityUnchangedWhenStoredHasNoEloState() {
        // Given — a stored game that was never Elo-updated
        GameSessionEntity stored = GameSessionEntity.builder().sessionId("game-3").build();
        GameSessionEntity rebuilt = GameSessionEntity.builder().sessionId("game-3").build();

        // When
        SessionMapper.carryEloState(stored, rebuilt);

        // Then
        assertFalse(rebuilt.isEloUpdated());
        assertNull(rebuilt.getEloChanges());
    }
}
