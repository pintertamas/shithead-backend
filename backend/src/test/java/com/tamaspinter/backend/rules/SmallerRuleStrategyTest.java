package com.tamaspinter.backend.rules;

import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.model.Suit;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.*;

class SmallerRuleStrategyTest {

    private final SmallerRuleStrategy strategy = new SmallerRuleStrategy();

    @Test
    void testLowerValueIsPlayable() {
        // Given
        Card prev = Card.builder().suit(Suit.HEARTS).value(9).rule(CardRule.SMALLER).alwaysPlayable(false).build();
        Card newCard = Card.builder().suit(Suit.SPADES).value(5).rule(CardRule.DEFAULT).alwaysPlayable(false).build();
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(prev);

        // When/Then
        assertTrue(strategy.canPlay(newCard, pile));
    }

    @Test
    void testEqualValueIsPlayable() {
        // Given
        Card prev = Card.builder().suit(Suit.HEARTS).value(6).rule(CardRule.SMALLER).alwaysPlayable(false).build();
        Card newCard = Card.builder().suit(Suit.SPADES).value(6).rule(CardRule.SMALLER).alwaysPlayable(false).build();
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(prev);

        // When/Then
        assertTrue(strategy.canPlay(newCard, pile));
    }

    @Test
    void testHigherValueIsNotPlayable() {
        // Given
        Card prev = Card.builder().suit(Suit.HEARTS).value(6).rule(CardRule.SMALLER).alwaysPlayable(false).build();
        Card newCard = Card.builder().suit(Suit.SPADES).value(9).rule(CardRule.DEFAULT).alwaysPlayable(false).build();
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(prev);

        // When/Then
        assertFalse(strategy.canPlay(newCard, pile));
    }

    @Test
    void testAlwaysPlayableIgnoresRule() {
        // Given — ace played after a 3 (would normally fail SMALLER rule)
        Card prev = Card.builder().suit(Suit.HEARTS).value(3).rule(CardRule.SMALLER).alwaysPlayable(false).build();
        Card newCard = Card.builder().suit(Suit.SPADES).value(14).rule(CardRule.DEFAULT).alwaysPlayable(true).build();
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(prev);

        // When/Then
        assertTrue(strategy.canPlay(newCard, pile));
    }

    @Test
    void testPlayableOnEmptyPile() {
        // Given
        Card newCard = Card.builder().suit(Suit.HEARTS).value(5).rule(CardRule.DEFAULT).alwaysPlayable(false).build();
        Deque<Card> pile = new ArrayDeque<>();

        // When/Then
        assertTrue(strategy.canPlay(newCard, pile));
    }
}

