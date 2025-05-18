package com.tamaspinter.backend.rules;

import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;

import java.util.Deque;
import java.util.Iterator;

// For TRANSPARENT: delegate to the rule of the last non-transparent card
public class TransparentRuleStrategy implements RuleStrategy {
    public boolean canPlay(Card newCard, Deque<Card> pile) {
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
