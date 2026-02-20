package com.tamaspinter.backend.rules;

import com.tamaspinter.backend.model.Card;

import java.util.Deque;

interface RuleStrategy {
    default boolean canPlay(Card newCard, Deque<Card> pile) {
        if (newCard.isAlwaysPlayable()) {
            return true;
        }
        if (pile.isEmpty()) {
            return true;
        }
        Card prev = pile.peekLast();
        return newCard.getValue() >= prev.getValue();
    }
}
