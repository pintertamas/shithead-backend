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
        Card prev = Card.builder().suit(Suit.HEARTS).value(2).rule(CardRule.JOKER).alwaysPlayable(true).build();
        Card newCard = Card.builder().suit(Suit.SPADES).value(3).rule(CardRule.DEFAULT).alwaysPlayable(false).build();
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(prev);

        // When/Then
        assertTrue(strategy.canPlay(newCard, pile));
    }

    @Test
    void testHighCardPlayableAfterJoker() {
        // Given — ace played after a joker
        Card prev = Card.builder().suit(Suit.HEARTS).value(2).rule(CardRule.JOKER).alwaysPlayable(true).build();
        Card newCard = Card.builder().suit(Suit.SPADES).value(14).rule(CardRule.DEFAULT).alwaysPlayable(false).build();
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(prev);

        // When/Then
        assertTrue(strategy.canPlay(newCard, pile));
    }

    @Test
    void testAnyCardPlayableOnAnyTopCard() {
        // Given — low card played after a king; joker rule always permits
        Card prev = Card.builder().suit(Suit.HEARTS).value(13).rule(CardRule.DEFAULT).alwaysPlayable(false).build();
        Card newCard = Card.builder().suit(Suit.DIAMONDS).value(3).rule(CardRule.DEFAULT).alwaysPlayable(false).build();
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

