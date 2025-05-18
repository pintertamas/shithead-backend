package com.tamaspinter.backend.rules;

import com.tamaspinter.backend.model.Card;

import java.util.Deque;

// For BURNER: pile is considered burned, so treat as empty
public class BurnerRuleStrategy implements RuleStrategy {
    public boolean canPlay(Card newCard, Deque<Card> pile) {
        return true;
    }
}
