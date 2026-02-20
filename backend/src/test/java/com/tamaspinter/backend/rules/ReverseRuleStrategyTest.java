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

class ReverseRuleStrategyTest {

    @Test
    void testAfterEffect() {
        // Given
        ReverseRuleStrategy strategy = new ReverseRuleStrategy();
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(Card.builder().suit(Suit.HEARTS).value(2).rule(CardRule.JOKER).alwaysPlayable(true).build());
        pile.add(Card.builder().suit(Suit.DIAMONDS).value(3).rule(CardRule.DEFAULT).alwaysPlayable(false).build());
        pile.add(Card.builder().suit(Suit.DIAMONDS).value(3).rule(CardRule.DEFAULT).alwaysPlayable(false).build());
        pile.add(Card.builder().suit(Suit.CLUBS).value(4).rule(CardRule.DEFAULT).alwaysPlayable(false).build());
        pile.add(Card.builder().suit(Suit.CLUBS).value(9).rule(CardRule.REVERSE).alwaysPlayable(false).build());

        List<Player> players = new ArrayList<>();
        Player player1 = Player.builder().playerId("1").username("test1").build();
        Player player2 = Player.builder().playerId("2").username("test2").build();
        Player player3 = Player.builder().playerId("3").username("test3").build();
        Player player4 = Player.builder().playerId("4").username("test4").build();
        players.add(player1);
        players.add(player2);
        players.add(player3);
        players.add(player4);
        Player currentPlayer = players.get(1);

        // When
        strategy.afterEffect(pile, players, currentPlayer);

        // Then
        assertEquals("test4", players.get(0).getUsername());
        assertEquals("test3", players.get(1).getUsername());
        assertEquals("test2", players.get(2).getUsername());
        assertEquals("test1", players.get(3).getUsername());
    }
}
