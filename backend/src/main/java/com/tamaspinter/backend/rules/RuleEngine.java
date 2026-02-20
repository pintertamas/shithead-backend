package com.tamaspinter.backend.rules;

import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.model.Player;

import java.util.Deque;
import java.util.List;
import java.util.Map;

public class RuleEngine {
    private static final Map<CardRule, RuleStrategy> STRATEGIES = Map.of(
        CardRule.DEFAULT, new DefaultRuleStrategy(),
        CardRule.JOKER, new JokerRuleStrategy(),
        CardRule.SMALLER, new SmallerRuleStrategy(),
        CardRule.TRANSPARENT, new TransparentRuleStrategy(),
        CardRule.REVERSE, new ReverseRuleStrategy(),
        CardRule.BURNER, new BurnerRuleStrategy()
    );

    public static RuleStrategy getStrategy(CardRule rule) {
        return STRATEGIES.get(rule);
    }

    public static boolean canPlay(Card newCard, Deque<Card> pile) {
        if (pile.isEmpty() || newCard.isAlwaysPlayable()) {
            return true;
        }
        Card prev = pile.peekLast();
        return getStrategy(prev.getRule()).canPlay(newCard, pile);
    }

    public static boolean shouldBurn(Deque<Card> pile, int n) {
        if (pile.isEmpty()) {
            return false;
        }
        int topValue = pile.peekLast().getValue();
        long count = pile.stream()
                         .skip(Math.max(0, pile.size() - n))
                         .filter(c -> c.getValue() == topValue)
                         .count();
        return count >= n;
    }

    public static void playAfterEffect(Card lastCard, Deque<Card> pile, Player currentPlayer, List<Player> players) {
        CardRule rule = lastCard.getRule();
        RuleStrategy strategy = getStrategy(rule);
        if (strategy instanceof AfterEffect) {
            ((AfterEffect) strategy).afterEffect(pile, players, currentPlayer);
        }
    }
}
