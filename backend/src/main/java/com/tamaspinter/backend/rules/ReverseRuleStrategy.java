package com.tamaspinter.backend.rules;

import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

public class ReverseRuleStrategy implements RuleStrategy, AfterEffect {
    @Override
    public void afterEffect(Deque<Card> pile, List<Player> players, Player currentPlayer) {
        reverseList(players);
    }

    private static <T> void reverseList(List<T> items) {
        List<T> copy = new ArrayList<>(items);
        Collections.reverse(copy);
        items.clear();
        items.addAll(copy);
    }
}



