package com.tamaspinter.backend.rules;

import com.tamaspinter.backend.model.Card;

import java.util.Deque;

// For SMALLER: newCard.value <= previous.value
public class SmallerRuleStrategy implements RuleStrategy {
    public boolean canPlay(Card newCard, Deque<Card> pile) {
        if (pile.isEmpty()) return true;
        Card prev = pile.peekLast();
        return newCard.getValue() <= prev.getValue();
    }
}
