package com.tamaspinter.backend.config;

import com.tamaspinter.backend.game.GameSession.InvalidReason;

/**
 * Player-facing texts for rejected plays and pickups. Messages never mention face-down card values.
 */
public final class PlayErrorMessages {

    public static final String NOT_YOUR_TURN = "It's not your turn yet.";
    static final String FALLBACK = "That play can't be made right now.";

    private PlayErrorMessages() {
    }

    /**
     * Text for an INVALID result.
     *
     * @param reason        why the session rejected the call; null falls back to a generic text
     * @param requiredValue the pile-top value a TOO_LOW / TOO_HIGH card was compared against
     */
    @SuppressWarnings("PMD.CyclomaticComplexity") // one arm per reason; splitting would only scatter the texts
    public static String forReason(InvalidReason reason, int requiredValue) {
        if (reason == null) {
            return FALLBACK;
        }
        return switch (reason) {
            case SETUP_NOT_COMPLETE -> "Wait until everyone is ready.";
            case GAME_FINISHED -> "This game has already ended.";
            case EMPTY_SELECTION -> "Select a card to play first.";
            case CARD_NOT_AVAILABLE -> "Those cards are not available to play.";
            case WRONG_ZONE -> "You can't play those cards right now: use your hand first, then your face-up cards,"
                    + " then the face-down ones.";
            case FACE_DOWN_ONE_AT_A_TIME -> "Flip your face-down cards one at a time.";
            case MIXED_VALUES -> "Cards played together must have the same value.";
            case TOO_LOW -> "That card is too low: play " + rankName(requiredValue) + " or higher.";
            case TOO_HIGH -> "That card is too high: play " + rankName(requiredValue) + " or lower.";
            case MIXED_HAND_FACEUP_NOT_ALLOWED ->
                    "Hand and face-up cards can only be played together when the draw pile is empty and that option is on.";
            case PILE_EMPTY -> "There is nothing to pick up.";
        };
    }

    /** Card rank as a player reads it: 2-10 as numbers, 11-14 as Jack, Queen, King, Ace. */
    static String rankName(int value) {
        return switch (value) {
            case 11 -> "Jack";
            case 12 -> "Queen";
            case 13 -> "King";
            case 14 -> "Ace";
            default -> String.valueOf(value);
        };
    }
}
