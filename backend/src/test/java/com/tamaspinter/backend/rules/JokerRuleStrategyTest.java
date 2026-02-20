package com.tamaspinter.backend.rules;

import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.model.Suit;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.*;

class JokerRuleStrategyTest {

    private final JokerRuleStrategy strategy = new JokerRuleStrategy();

    @Test
    void testLowCardPlayableOnHighValue() {
        // Given — 3 played after a joker (2) despite being higher; joker always allows
        Card prev = new Card(Suit.HEARTS, 2, CardRule.JOKER, true);
        Card newCard = new Card(Suit.SPADES, 3, CardRule.DEFAULT, false);
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(prev);

        // When/Then
        assertTrue(strategy.canPlay(newCard, pile));
    }

    @Test
    void testHighCardPlayableAfterJoker() {
        // Given — ace played after a joker
        Card prev = new Card(Suit.HEARTS, 2, CardRule.JOKER, true);
        Card newCard = new Card(Suit.SPADES, 14, CardRule.DEFAULT, false);
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(prev);

        // When/Then
        assertTrue(strategy.canPlay(newCard, pile));
    }

    @Test
    void testAnyCardPlayableOnAnyTopCard() {
        // Given — low card played after a king; joker rule always permits
        Card prev = new Card(Suit.HEARTS, 13, CardRule.DEFAULT, false);
        Card newCard = new Card(Suit.DIAMONDS, 3, CardRule.DEFAULT, false);
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(prev);

        // When/Then
        assertTrue(strategy.canPlay(newCard, pile));
    }

    @Test
    void testPlayableOnEmptyPile() {
        // Given
        Card newCard = new Card(Suit.HEARTS, 5, CardRule.DEFAULT, false);
        Deque<Card> pile = new ArrayDeque<>();

        // When/Then
        assertTrue(strategy.canPlay(newCard, pile));
    }
}
