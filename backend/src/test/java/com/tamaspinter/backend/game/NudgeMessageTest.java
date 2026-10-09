package com.tamaspinter.backend.game;

import com.tamaspinter.backend.entity.PlayerEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NudgeMessageTest {

    @Test
    void build_withKnownPlayer_relaysTypeUserNameAndTimestamp() {
        // Given
        List<PlayerEntity> players = List.of(
                PlayerEntity.builder().playerId("u1").username("Anna").build(),
                PlayerEntity.builder().playerId("u2").username("Bela").build());

        // When
        Map<String, Object> payload = NudgeMessage.build(players, "u2", 1234L);

        // Then
        assertEquals(Map.of("type", "nudge", "userId", "u2", "username", "Bela", "ts", 1234L), payload);
    }

    @Test
    void build_withUnnamedPlayer_fallsBackToUnknown() {
        // Given
        List<PlayerEntity> players = List.of(PlayerEntity.builder().playerId("u1").build());

        // When
        Map<String, Object> payload = NudgeMessage.build(players, "u1", 5L);

        // Then
        assertEquals("Unknown", payload.get("username"));
    }
}
