package com.tamaspinter.backend.rules;

import com.tamaspinter.backend.model.Card;

import java.util.Deque;

public class SmallerRuleStrategy implements RuleStrategy {
    @Override
    public boolean canPlay(Card newCard, Deque<Card> pile) {
        if (newCard.isAlwaysPlayable()) {
            return true;
        }
        if (pile.isEmpty()) {
            return true;
        }
        Card prev = pile.peekLast();
        return newCard.getValue() <= prev.getValue();
    }
}


