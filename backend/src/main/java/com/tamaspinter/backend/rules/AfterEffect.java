package com.tamaspinter.backend.rules;

import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.Player;

import java.util.Deque;
import java.util.List;

public interface AfterEffect {
    default void afterEffect(Deque<Card> pile, List<Player> players, Player currentPlayer) {}
}
