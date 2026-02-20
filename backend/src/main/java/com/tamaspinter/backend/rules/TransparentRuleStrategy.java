package com.tamaspinter.backend.rules;

import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;

import java.util.Deque;
import java.util.Iterator;

public class TransparentRuleStrategy implements RuleStrategy {
    @Override
    public boolean canPlay(Card newCard, Deque<Card> pile) {
        if (newCard.isAlwaysPlayable()) {
            return true;
        }
        Iterator<Card> desc = pile.descendingIterator();
        while (desc.hasNext()) {
            Card top = desc.next();
            if (top.getRule() != CardRule.TRANSPARENT) {
                return RuleEngine.getStrategy(top.getRule())
                        .canPlay(newCard, pile);
            }
        }
        return true;
    }
}


