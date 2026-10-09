package com.tamaspinter.backend.rules;

import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;

import java.util.ArrayDeque;
import java.util.Deque;

public class TransparentRuleStrategy implements RuleStrategy {
    @Override
    public boolean canPlay(Card newCard, Deque<Card> pile) {
        if (newCard.isAlwaysPlayable()) {
            return true;
        }
        Deque<Card> effectivePile = new ArrayDeque<>(pile);
        while (!effectivePile.isEmpty()) {
            Card top = effectivePile.peekLast();
            if (top.getRule() != CardRule.TRANSPARENT) {
                return RuleEngine.getStrategy(top.getRule())
                        .canPlay(newCard, effectivePile);
            }
            effectivePile.removeLast();
        }
        return true;
    }
}


