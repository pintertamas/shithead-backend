package com.tamaspinter.backend.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayDeque;
import java.util.Deque;

@Getter
public class Player {
    private final String playerId;
    private final String username;
    private final Deque<Card> hand = new ArrayDeque<>();
    private final Deque<Card> faceUp = new ArrayDeque<>();
    private final Deque<Card> faceDown = new ArrayDeque<>();
    @Setter
    private boolean out = false;

    public Player(String playerId, String username) {
        this.playerId = playerId;
        this.username = username;
    }
}
