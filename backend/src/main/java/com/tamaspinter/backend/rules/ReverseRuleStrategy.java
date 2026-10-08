package com.tamaspinter.backend.rules;

import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.Player;

import java.util.Deque;
import java.util.List;

public class ReverseRuleStrategy implements RuleStrategy, AfterEffect {
    @Override
    public void afterEffect(Deque<Card> pile, List<Player> players, Player currentPlayer) {
        int currentIndex = players.indexOf(currentPlayer);
        if (currentIndex < 0 || players.size() < 2) {
            return;
        }

        List<Player> originalOrder = List.copyOf(players);
        int playerCount = players.size();
        for (int offset = 0; offset < playerCount; offset++) {
            int destinationIndex = (currentIndex + offset) % playerCount;
            int sourceIndex = (currentIndex - offset + playerCount) % playerCount;
            players.set(destinationIndex, originalOrder.get(sourceIndex));
        }
    }
}



