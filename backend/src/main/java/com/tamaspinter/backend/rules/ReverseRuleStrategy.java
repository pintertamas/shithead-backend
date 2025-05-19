package com.tamaspinter.backend.rules;

import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.Player;

import java.util.*;

// For REVERSE: pile gets reversed as after effect
public class ReverseRuleStrategy implements RuleStrategy, AfterEffect {
    public void afterEffect(Deque<Card> pile, List<Player> players, Player currentPlayer) {
        reverseList(players);
    }

    private static <Player> void reverseList(List<Player> players) {
        List<Player> tmp = new ArrayList<>(players);
        Collections.reverse(tmp);
        players.clear();
        players.addAll(tmp);
    }
}

