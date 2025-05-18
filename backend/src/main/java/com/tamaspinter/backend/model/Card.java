package com.tamaspinter.backend.model;

public class Card {
    private final int value;
    private final CardRule rule;
    private final boolean alwaysPlayable;

    public Card(int value, CardRule rule, boolean alwaysPlayable) {
        this.value = value;
        this.rule = rule;
        this.alwaysPlayable = alwaysPlayable;
    }

    public int getValue() { return value; }
    public CardRule getRule() { return rule; }
    public boolean isAlwaysPlayable() { return alwaysPlayable; }
}