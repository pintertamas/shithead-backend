package com.tamaspinter.backend.rules;

import com.tamaspinter.backend.model.Card;

import java.util.Deque;

interface RuleStrategy {
    boolean canPlay(Card newCard, Deque<Card> pile);
}