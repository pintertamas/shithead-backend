package com.tamaspinter.backend.model;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayDeque;
import java.util.Deque;

@Getter
@Builder
public class Player {
    private final String playerId;
    private final String username;
    @Builder.Default
    private final Deque<Card> hand = new ArrayDeque<>();
    @Builder.Default
    private final Deque<Card> faceUp = new ArrayDeque<>();
    @Builder.Default
    private final Deque<Card> faceDown = new ArrayDeque<>();
    @Setter
    private boolean out;
}
