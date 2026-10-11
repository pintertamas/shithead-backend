package com.tamaspinter.backend.game;

import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.Player;

import java.util.List;

/**
 * Hears what everyone at the table can see happen: cards played onto the pile, piles picked up (the pile was face
 * up, and a failed blind flip or face-up play is revealed), piles burned, and face-up cards taken into the hand
 * during setup. It is never told about draws, dealt hands or face-down cards.
 */
public interface PublicMoveObserver {
    /** Observes nothing. */
    PublicMoveObserver NONE = new PublicMoveObserver() { };

    /** Cards the player put on the pile, in play order. */
    default void cardsPlayed(Player player, List<Card> cards) {
        // ignored unless overridden
    }

    /** Every card that went into the player's hand by picking up: the pile and any revealed failed cards. */
    default void cardsPickedUp(Player player, List<Card> cards) {
        // ignored unless overridden
    }

    /** Cards removed from the game by a burn. */
    default void pileBurned(List<Card> cards) {
        // ignored unless overridden
    }

    /** Face-up cards the player swapped into their hand during setup. */
    default void faceUpTakenIntoHand(Player player, List<Card> cards) {
        // ignored unless overridden
    }
}
