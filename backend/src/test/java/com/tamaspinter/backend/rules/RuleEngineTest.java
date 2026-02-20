package com.tamaspinter.backend.rules;

import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.model.Player;
import com.tamaspinter.backend.model.Suit;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuleEngineTest {

    @Test
    void testCanPlayOnEmptyPile() {
        // Given
        Card card = new Card(Suit.HEARTS, 7, CardRule.DEFAULT, false);
        Deque<Card> pile = new ArrayDeque<>();

        // When/Then
        assertTrue(RuleEngine.canPlay(card, pile));
    }

    @Test
    void testCanPlayAlwaysPlayableCard() {
        // Given
        Card alwaysPlayableCard = new Card(Suit.HEARTS, 7, CardRule.DEFAULT, true);
        Card topCard = new Card(Suit.SPADES, 10, CardRule.DEFAULT, false);
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(topCard);

        // When/Then
        assertTrue(RuleEngine.canPlay(alwaysPlayableCard, pile));
    }

    @Test
    void testShouldBurnWithFourSameValues() {
        // Given
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(new Card(Suit.HEARTS, 7, CardRule.DEFAULT, false));
        pile.add(new Card(Suit.DIAMONDS, 7, CardRule.DEFAULT, false));
        pile.add(new Card(Suit.CLUBS, 7, CardRule.DEFAULT, false));
        pile.add(new Card(Suit.SPADES, 7, CardRule.DEFAULT, false));

        // When/Then
        assertTrue(RuleEngine.shouldBurn(pile, 4));
    }

    @Test
    void testShouldNotBurnWithDifferentValues() {
        // Given
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(new Card(Suit.HEARTS, 7, CardRule.DEFAULT, false));
        pile.add(new Card(Suit.DIAMONDS, 8, CardRule.DEFAULT, false));
        pile.add(new Card(Suit.CLUBS, 9, CardRule.DEFAULT, false));
        pile.add(new Card(Suit.SPADES, 10, CardRule.DEFAULT, false));

        // When/Then
        assertFalse(RuleEngine.shouldBurn(pile, 4));
    }

    @Test
    void testShouldNotBurnWithFewerThanNMatchingCards() {
        // Given — only 3 matching cards, burnCount is 4
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(new Card(Suit.HEARTS, 5, CardRule.DEFAULT, false));
        pile.add(new Card(Suit.DIAMONDS, 7, CardRule.DEFAULT, false));
        pile.add(new Card(Suit.CLUBS, 7, CardRule.DEFAULT, false));
        pile.add(new Card(Suit.SPADES, 7, CardRule.DEFAULT, false));

        // When/Then
        assertFalse(RuleEngine.shouldBurn(pile, 4));
    }

    @Test
    void testCanPlayWithTransparentOnTop() {
        // Given — transparent card on top; RuleEngine delegates through it
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(new Card(Suit.HEARTS, 5, CardRule.DEFAULT, false));
        pile.add(new Card(Suit.SPADES, 8, CardRule.TRANSPARENT, false));

        Card playable = new Card(Suit.CLUBS, 9, CardRule.DEFAULT, false);
        Card notPlayable = new Card(Suit.CLUBS, 3, CardRule.DEFAULT, false);

        // When/Then
        assertTrue(RuleEngine.canPlay(playable, pile));
        assertFalse(RuleEngine.canPlay(notPlayable, pile));
    }

    @Test
    void testPlayAfterEffectBurnerClearsPile() {
        // Given
        Card burner = new Card(Suit.CLUBS, 10, CardRule.BURNER, false);
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(new Card(Suit.HEARTS, 5, CardRule.DEFAULT, false));
        pile.add(new Card(Suit.DIAMONDS, 7, CardRule.DEFAULT, false));
        pile.add(burner);
        List<Player> players = List.of(new Player("1", "alice"), new Player("2", "bob"));
        Player current = players.get(0);

        // When
        RuleEngine.playAfterEffect(burner, pile, current, players);

        // Then
        assertEquals(0, pile.size());
    }

    @Test
    void testPlayAfterEffectReverseReversesPlayers() {
        // Given
        Card reverse = new Card(Suit.CLUBS, 9, CardRule.REVERSE, false);
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(reverse);
        List<Player> players = new ArrayList<>();
        players.add(new Player("1", "alice"));
        players.add(new Player("2", "bob"));
        players.add(new Player("3", "carol"));
        Player current = players.get(0);

        // When
        RuleEngine.playAfterEffect(reverse, pile, current, players);

        // Then
        assertEquals("carol", players.get(0).getUsername());
        assertEquals("bob", players.get(1).getUsername());
        assertEquals("alice", players.get(2).getUsername());
    }

    @Test
    void testPlayAfterEffectDefaultIsNoOp() {
        // Given — DEFAULT card has no after-effect
        Card defaultCard = new Card(Suit.HEARTS, 7, CardRule.DEFAULT, false);
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(new Card(Suit.DIAMONDS, 5, CardRule.DEFAULT, false));
        pile.add(defaultCard);
        List<Player> players = new ArrayList<>();
        players.add(new Player("1", "alice"));
        players.add(new Player("2", "bob"));
        Player current = players.get(0);

        // When
        RuleEngine.playAfterEffect(defaultCard, pile, current, players);

        // Then — pile and player order unchanged
        assertEquals(2, pile.size());
        assertEquals("alice", players.get(0).getUsername());
        assertEquals("bob", players.get(1).getUsername());
    }

    @Test
    void testShouldBurnReturnsFalseForEmptyPile() {
        // Given
        Deque<Card> pile = new ArrayDeque<>();

        // When/Then
        assertFalse(RuleEngine.shouldBurn(pile, 4));
    }
}