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
        Card card = Card.builder().suit(Suit.HEARTS).value(7).rule(CardRule.DEFAULT).alwaysPlayable(false).build();
        Deque<Card> pile = new ArrayDeque<>();

        // When/Then
        assertTrue(RuleEngine.canPlay(card, pile));
    }

    @Test
    void testCanPlayAlwaysPlayableCard() {
        // Given
        Card alwaysPlayableCard = Card.builder().suit(Suit.HEARTS).value(7).rule(CardRule.DEFAULT).alwaysPlayable(true).build();
        Card topCard = Card.builder().suit(Suit.SPADES).value(10).rule(CardRule.DEFAULT).alwaysPlayable(false).build();
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(topCard);

        // When/Then
        assertTrue(RuleEngine.canPlay(alwaysPlayableCard, pile));
    }

    @Test
    void testShouldBurnWithFourSameValues() {
        // Given
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(Card.builder().suit(Suit.HEARTS).value(7).rule(CardRule.DEFAULT).alwaysPlayable(false).build());
        pile.add(Card.builder().suit(Suit.DIAMONDS).value(7).rule(CardRule.DEFAULT).alwaysPlayable(false).build());
        pile.add(Card.builder().suit(Suit.CLUBS).value(7).rule(CardRule.DEFAULT).alwaysPlayable(false).build());
        pile.add(Card.builder().suit(Suit.SPADES).value(7).rule(CardRule.DEFAULT).alwaysPlayable(false).build());

        // When/Then
        assertTrue(RuleEngine.shouldBurn(pile, 4));
    }

    @Test
    void testShouldNotBurnWithDifferentValues() {
        // Given
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(Card.builder().suit(Suit.HEARTS).value(7).rule(CardRule.DEFAULT).alwaysPlayable(false).build());
        pile.add(Card.builder().suit(Suit.DIAMONDS).value(8).rule(CardRule.DEFAULT).alwaysPlayable(false).build());
        pile.add(Card.builder().suit(Suit.CLUBS).value(9).rule(CardRule.DEFAULT).alwaysPlayable(false).build());
        pile.add(Card.builder().suit(Suit.SPADES).value(10).rule(CardRule.DEFAULT).alwaysPlayable(false).build());

        // When/Then
        assertFalse(RuleEngine.shouldBurn(pile, 4));
    }

    @Test
    void testShouldNotBurnWithFewerThanNMatchingCards() {
        // Given — only 3 matching cards, burnCount is 4
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(Card.builder().suit(Suit.HEARTS).value(5).rule(CardRule.DEFAULT).alwaysPlayable(false).build());
        pile.add(Card.builder().suit(Suit.DIAMONDS).value(7).rule(CardRule.DEFAULT).alwaysPlayable(false).build());
        pile.add(Card.builder().suit(Suit.CLUBS).value(7).rule(CardRule.DEFAULT).alwaysPlayable(false).build());
        pile.add(Card.builder().suit(Suit.SPADES).value(7).rule(CardRule.DEFAULT).alwaysPlayable(false).build());

        // When/Then
        assertFalse(RuleEngine.shouldBurn(pile, 4));
    }

    @Test
    void testCanPlayWithTransparentOnTop() {
        // Given — transparent card on top; RuleEngine delegates through it
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(Card.builder().suit(Suit.HEARTS).value(5).rule(CardRule.DEFAULT).alwaysPlayable(false).build());
        pile.add(Card.builder().suit(Suit.SPADES).value(8).rule(CardRule.TRANSPARENT).alwaysPlayable(false).build());

        Card playable = Card.builder().suit(Suit.CLUBS).value(9).rule(CardRule.DEFAULT).alwaysPlayable(false).build();
        Card notPlayable = Card.builder().suit(Suit.CLUBS).value(3).rule(CardRule.DEFAULT).alwaysPlayable(false).build();

        // When/Then
        assertTrue(RuleEngine.canPlay(playable, pile));
        assertFalse(RuleEngine.canPlay(notPlayable, pile));
    }

    @Test
    void testPlayAfterEffectBurnerClearsPile() {
        // Given
        Card burner = Card.builder().suit(Suit.CLUBS).value(10).rule(CardRule.BURNER).alwaysPlayable(false).build();
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(Card.builder().suit(Suit.HEARTS).value(5).rule(CardRule.DEFAULT).alwaysPlayable(false).build());
        pile.add(Card.builder().suit(Suit.DIAMONDS).value(7).rule(CardRule.DEFAULT).alwaysPlayable(false).build());
        pile.add(burner);
        List<Player> players = List.of(Player.builder().playerId("1").username("alice").build(), Player.builder().playerId("2").username("bob").build());
        Player current = players.get(0);

        // When
        RuleEngine.playAfterEffect(burner, pile, current, players);

        // Then
        assertEquals(0, pile.size());
    }

    @Test
    void testPlayAfterEffectReverseReversesPlayers() {
        // Given
        Card reverse = Card.builder().suit(Suit.CLUBS).value(9).rule(CardRule.REVERSE).alwaysPlayable(false).build();
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(reverse);
        List<Player> players = new ArrayList<>();
        players.add(Player.builder().playerId("1").username("alice").build());
        players.add(Player.builder().playerId("2").username("bob").build());
        players.add(Player.builder().playerId("3").username("carol").build());
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
        Card defaultCard = Card.builder().suit(Suit.HEARTS).value(7).rule(CardRule.DEFAULT).alwaysPlayable(false).build();
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(Card.builder().suit(Suit.DIAMONDS).value(5).rule(CardRule.DEFAULT).alwaysPlayable(false).build());
        pile.add(defaultCard);
        List<Player> players = new ArrayList<>();
        players.add(Player.builder().playerId("1").username("alice").build());
        players.add(Player.builder().playerId("2").username("bob").build());
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
