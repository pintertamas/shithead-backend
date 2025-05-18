package com.tamaspinter.backend.rules;

import com.tamaspinter.backend.model.Card;

import java.util.Deque;

// For JOKER: any new card allowed
public class JokerRuleStrategy implements RuleStrategy {
    public boolean canPlay(Card newCard, Deque<Card> pile) {
        return true;
    }
}
