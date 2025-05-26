package com.tamaspinter.backend.rules;

import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

// For BURNER: pile gets burned as after effect
public class BurnerRuleStrategy implements RuleStrategy, AfterEffect {
    public boolean canPlay(Card newCard, Deque<Card> pile) {
        return true;
    }

    public void afterEffect(Deque<Card> pile, List<Player> players, Player currentPlayer) {
        pile.clear();
    }
}

