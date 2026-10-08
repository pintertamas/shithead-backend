package com.tamaspinter.backend.model;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

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
    @Builder.Default
    @Setter
    private boolean ready = true;
    @Setter
    private boolean out;

    public void sortHand() {
        sortCards(hand);
    }

    public void sortFaceUp() {
        sortCards(faceUp);
    }

    private void sortCards(Deque<Card> cards) {
        List<Card> sorted = new ArrayList<>(cards);
        sorted.sort(Comparator.comparingInt(Card::getValue).thenComparing(card -> card.getSuit().name()));
        cards.clear();
        cards.addAll(sorted);
    }
}
