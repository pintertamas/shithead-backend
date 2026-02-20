package com.tamaspinter.backend.rules;

import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.model.Suit;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TransparentRuleStrategy delegates to the rule of the first non-transparent card
 * below, but passes the original pile — so the value compared is always the
 * transparent card (8) on top, not the card below it.
 */
class TransparentRuleStrategyTest {

    private final TransparentRuleStrategy strategy = new TransparentRuleStrategy();

    // --- DEFAULT rule below transparent ---

    @Test
    void testDelegatesToDefaultRuleWhenBelowIsDefault() {
        // Given — pile: [5 DEFAULT, 8 TRANSPARENT]; delegates to DEFAULT → newCard >= 8
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(new Card(Suit.HEARTS, 5, CardRule.DEFAULT, false));
        pile.add(new Card(Suit.SPADES, 8, CardRule.TRANSPARENT, false));

        Card playable = new Card(Suit.CLUBS, 9, CardRule.DEFAULT, false);
        Card notPlayable = new Card(Suit.CLUBS, 5, CardRule.DEFAULT, false);

        // When/Then
        assertTrue(strategy.canPlay(playable, pile));
        assertFalse(strategy.canPlay(notPlayable, pile));
    }

    // --- SMALLER rule below transparent ---

    @Test
    void testDelegatesToSmallerRuleWhenBelowIsSmaller() {
        // Given — pile: [9 SMALLER, 8 TRANSPARENT]; delegates to SMALLER → newCard <= 8
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(new Card(Suit.HEARTS, 9, CardRule.SMALLER, false));
        pile.add(new Card(Suit.SPADES, 8, CardRule.TRANSPARENT, false));

        Card playable = new Card(Suit.CLUBS, 5, CardRule.DEFAULT, false);
        Card notPlayable = new Card(Suit.CLUBS, 10, CardRule.DEFAULT, false);

        // When/Then
        assertTrue(strategy.canPlay(playable, pile));
        assertFalse(strategy.canPlay(notPlayable, pile));
    }

    // --- Multiple stacked transparent cards ---

    @Test
    void testWalksThroughMultipleTransparentCards() {
        // Given — pile: [5 DEFAULT, 8 TRANSPARENT, 8 TRANSPARENT]; still delegates to DEFAULT
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(new Card(Suit.HEARTS, 5, CardRule.DEFAULT, false));
        pile.add(new Card(Suit.SPADES, 8, CardRule.TRANSPARENT, false));
        pile.add(new Card(Suit.DIAMONDS, 8, CardRule.TRANSPARENT, false));

        Card playable = new Card(Suit.CLUBS, 9, CardRule.DEFAULT, false);
        Card notPlayable = new Card(Suit.CLUBS, 3, CardRule.DEFAULT, false);

        // When/Then
        assertTrue(strategy.canPlay(playable, pile));
        assertFalse(strategy.canPlay(notPlayable, pile));
    }

    // --- All transparent pile (no non-transparent card below) ---

    @Test
    void testAllTransparentPileIsAlwaysPlayable() {
        // Given — pile consists entirely of transparent cards → returns true
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(new Card(Suit.HEARTS, 8, CardRule.TRANSPARENT, false));
        pile.add(new Card(Suit.SPADES, 8, CardRule.TRANSPARENT, false));

        Card anyCard = new Card(Suit.CLUBS, 3, CardRule.DEFAULT, false);

        // When/Then
        assertTrue(strategy.canPlay(anyCard, pile));
    }

    // --- Always-playable card bypasses rule entirely ---

    @Test
    void testAlwaysPlayableCardIgnoresTransparentRule() {
        // Given — even a low card can be played if it's marked always-playable
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(new Card(Suit.HEARTS, 5, CardRule.DEFAULT, false));
        pile.add(new Card(Suit.SPADES, 8, CardRule.TRANSPARENT, false));

        Card alwaysPlayable = new Card(Suit.CLUBS, 2, CardRule.JOKER, true);

        // When/Then
        assertTrue(strategy.canPlay(alwaysPlayable, pile));
    }
}
