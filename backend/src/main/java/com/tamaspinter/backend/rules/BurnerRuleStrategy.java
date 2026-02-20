package com.tamaspinter.backend.rules;

import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.Player;

import java.util.Deque;
import java.util.List;

public class BurnerRuleStrategy implements RuleStrategy, AfterEffect {
    @Override
    public boolean canPlay(Card newCard, Deque<Card> pile) {
        return true;
    }

    @Override
    public void afterEffect(Deque<Card> pile, List<Player> players, Player currentPlayer) {
        pile.clear();
    }
}



