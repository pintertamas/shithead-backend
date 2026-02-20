package com.tamaspinter.backend.model;

import lombok.Builder;
import lombok.Getter;

@Getter
public class Card {
    private final Suit suit;
    private final int value;
    private final CardRule rule;
    private final boolean alwaysPlayable;

    @Builder
    public Card(Suit suit, int value, CardRule rule, boolean alwaysPlayable) {
        this.suit = suit;
        this.value = value;
        this.rule = rule;
        this.alwaysPlayable = alwaysPlayable;
    }

    @Override
    public String toString() {
        String playableText = alwaysPlayable ? "always playable" : "not always playable";
        return value + " of " + suit + " [Rule: " + rule + " (" + playableText + ")]";
    }
}
