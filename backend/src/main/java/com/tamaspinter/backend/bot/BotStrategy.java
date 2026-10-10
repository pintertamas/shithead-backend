package com.tamaspinter.backend.bot;

import com.tamaspinter.backend.game.CardSelection;
import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.model.Card;

import java.util.List;
import java.util.Random;

/**
 * How one kind of bot decides. Strategies only choose; legality always comes from {@code GameSession}, which
 * lists the legal plays and applies the chosen one with the same checks it uses for humans.
 */
public interface BotStrategy {

    /** Swaps to make during setup, using index positions in the given hand and face-up lists. */
    SetupSwap chooseSetupSwap(List<Card> hand, List<Card> faceUp, GameConfig config);

    /**
     * One entry of {@code legalPlays}, or null to pick up the pile. {@code legalPlays} is never empty when the pile
     * is empty. A choice that is not one of the legal plays is replaced by a safe fallback. {@code random} is the
     * only source of randomness a strategy may use, so tests and simulations can seed it.
     */
    List<CardSelection> choosePlay(BotView view, List<List<CardSelection>> legalPlays, Random random);
}
