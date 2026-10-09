package com.tamaspinter.backend.game;

import com.tamaspinter.backend.entity.PlayerEntity;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the relayed "nudge" payload (the farting sound sent to everyone at the table). Nothing is stored or
 * logged; the payload only carries who pressed the button and when.
 */
public final class NudgeMessage {

    private NudgeMessage() {
    }

    public static Map<String, Object> build(List<PlayerEntity> players, String userId, long timestampMillis) {
        String username = players.stream()
                .filter(player -> userId.equals(player.getPlayerId()))
                .map(PlayerEntity::getUsername)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("Unknown");
        return Map.of(
                "type", "nudge",
                "userId", userId,
                "username", username,
                "ts", timestampMillis);
    }
}
