package com.tamaspinter.backend.game;

import com.tamaspinter.backend.model.CardRule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GameConfigTest {

    private final GameConfig config = GameConfig.defaultGameConfig();

    // =========================================================================
    // Default counts
    // =========================================================================

    @Test
    void testDefaultConfig_hasCorrectCounts() {
        assertEquals(3, config.getFaceDownCount());
        assertEquals(3, config.getFaceUpCount());
        assertEquals(3, config.getHandCount());
        assertEquals(4, config.getBurnCount());
    }

    // =========================================================================
    // getCardRule
    // =========================================================================

    @Test
    void testGetCardRule_returnsCorrectRulePerSpecialValue() {
        assertEquals(CardRule.JOKER,       config.getCardRule(2));
        assertEquals(CardRule.SMALLER,     config.getCardRule(6));
        assertEquals(CardRule.TRANSPARENT, config.getCardRule(8));
        assertEquals(CardRule.REVERSE,     config.getCardRule(9));
        assertEquals(CardRule.BURNER,      config.getCardRule(10));
    }

    @Test
    void testGetCardRule_returnsDefaultForUnmappedValues() {
        assertEquals(CardRule.DEFAULT, config.getCardRule(3));
        assertEquals(CardRule.DEFAULT, config.getCardRule(5));
        assertEquals(CardRule.DEFAULT, config.getCardRule(7));
        assertEquals(CardRule.DEFAULT, config.getCardRule(11));
        assertEquals(CardRule.DEFAULT, config.getCardRule(14));
    }

    // =========================================================================
    // isAlwaysPlayable
    // =========================================================================

    @Test
    void testIsAlwaysPlayable_trueForConfiguredValues() {
        assertTrue(config.isAlwaysPlayable(2));  // JOKER
        assertTrue(config.isAlwaysPlayable(8));  // TRANSPARENT
    }

    @Test
    void testIsAlwaysPlayable_falseForOthers() {
        assertFalse(config.isAlwaysPlayable(3));
        assertFalse(config.isAlwaysPlayable(7));
        assertFalse(config.isAlwaysPlayable(10));
    }

    // =========================================================================
    // canPlayAgain
    // =========================================================================

    @Test
    void testCanPlayAgain_trueForBurner() {
        assertTrue(config.canPlayAgain(10));
    }

    @Test
    void testCanPlayAgain_falseForOthers() {
        assertFalse(config.canPlayAgain(2));
        assertFalse(config.canPlayAgain(7));
        assertFalse(config.canPlayAgain(9));
    }
}

