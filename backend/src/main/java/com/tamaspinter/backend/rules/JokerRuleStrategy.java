package com.tamaspinter.backend.rules;

import com.tamaspinter.backend.model.Card;

import java.util.Deque;

public class JokerRuleStrategy implements RuleStrategy {
    @Override
    public boolean canPlay(Card newCard, Deque<Card> pile) {
        return true;
    }
}


