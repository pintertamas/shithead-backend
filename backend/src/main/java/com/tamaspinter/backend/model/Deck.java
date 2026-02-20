package com.tamaspinter.backend.model;

import com.tamaspinter.backend.game.GameConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

public class Deck {
    private final Deque<Card> cards = new ArrayDeque<>();

    public Deck(int decksCount, GameConfig config) {
        List<Card> list = new ArrayList<>();
        for (int d = 0; d < decksCount; d++) {
            for (Suit s : Suit.values()) {
                for (int v = 2; v <= 14; v++) {
                    list.add(Card.builder()
                            .suit(s)
                            .value(v)
                            .rule(config.getCardRule(v))
                            .alwaysPlayable(config.isAlwaysPlayable(v))
                            .build());
                }
            }
        }
        Collections.shuffle(list);
        cards.addAll(list);
    }

    public Deck(List<Card> cards) {
        this.cards.addAll(cards);
    }

    public List<Card> getCards() {
        return new ArrayList<>(cards);
    }

    public Optional<Card> draw() {
        return Optional.ofNullable(cards.pollFirst());
    }

    public boolean isEmpty() {
        return cards.isEmpty();
    }
}

