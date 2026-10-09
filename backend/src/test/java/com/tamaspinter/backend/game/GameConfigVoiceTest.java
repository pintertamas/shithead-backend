package com.tamaspinter.backend.game;

import com.tamaspinter.backend.entity.GameConfigEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameConfigVoiceTest {

    @Test
    void voiceIsOffByDefault() {
        assertFalse(GameConfig.defaultGameConfig().isVoiceEnabled());
    }

    @Test
    void voiceFlagRoundTripsThroughEntity() {
        GameConfig enabled = GameConfig.builder().voiceEnabled(true).build();

        assertTrue(GameConfig.fromEntity(enabled.toEntity()).isVoiceEnabled());
    }

    @Test
    void missingAttributeReadsAsFalse() {
        GameConfigEntity entity = GameConfigEntity.builder()
                .cardRules(new HashMap<>())
                .alwaysPlayable(new ArrayList<>())
                .canPlayAgain(new ArrayList<>())
                .build();

        assertFalse(GameConfig.fromEntity(entity).isVoiceEnabled());
    }
}
