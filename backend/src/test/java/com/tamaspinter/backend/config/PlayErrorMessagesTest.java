package com.tamaspinter.backend.config;

import com.tamaspinter.backend.game.GameSession.InvalidReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PlayErrorMessagesTest {

    @Test
    void notYourTurn_hasItsOwnMessage() {
        // Given / When / Then
        assertEquals("It's not your turn yet.", PlayErrorMessages.NOT_YOUR_TURN);
    }

    @Test
    void everyReason_mapsToItsOwnMessage() {
        // Given — every reason, with the value only used by TOO_LOW / TOO_HIGH
        // When / Then
        assertEquals("Wait until everyone is ready.",
                PlayErrorMessages.forReason(InvalidReason.SETUP_NOT_COMPLETE, 0));
        assertEquals("This game has already ended.",
                PlayErrorMessages.forReason(InvalidReason.GAME_FINISHED, 0));
        assertEquals("Select a card to play first.",
                PlayErrorMessages.forReason(InvalidReason.EMPTY_SELECTION, 0));
        assertEquals("Those cards are not available to play.",
                PlayErrorMessages.forReason(InvalidReason.CARD_NOT_AVAILABLE, 0));
        assertEquals("You can't play those cards right now: use your hand first, then your face-up cards,"
                        + " then the face-down ones.",
                PlayErrorMessages.forReason(InvalidReason.WRONG_ZONE, 0));
        assertEquals("Flip your face-down cards one at a time.",
                PlayErrorMessages.forReason(InvalidReason.FACE_DOWN_ONE_AT_A_TIME, 0));
        assertEquals("Cards played together must have the same value.",
                PlayErrorMessages.forReason(InvalidReason.MIXED_VALUES, 0));
        assertEquals("Hand and face-up cards can only be played together when the draw pile is empty and that option is on.",
                PlayErrorMessages.forReason(InvalidReason.MIXED_HAND_FACEUP_NOT_ALLOWED, 0));
        assertEquals("There is nothing to pick up.",
                PlayErrorMessages.forReason(InvalidReason.PILE_EMPTY, 0));
    }

    @Test
    void tooLowAndTooHigh_nameTheRankOfThePileTop() {
        // Given — a number, and the face cards that have names
        // When / Then
        assertEquals("That card is too low: play 9 or higher.",
                PlayErrorMessages.forReason(InvalidReason.TOO_LOW, 9));
        assertEquals("That card is too low: play Jack or higher.",
                PlayErrorMessages.forReason(InvalidReason.TOO_LOW, 11));
        assertEquals("That card is too low: play Queen or higher.",
                PlayErrorMessages.forReason(InvalidReason.TOO_LOW, 12));
        assertEquals("That card is too low: play King or higher.",
                PlayErrorMessages.forReason(InvalidReason.TOO_LOW, 13));
        assertEquals("That card is too low: play Ace or higher.",
                PlayErrorMessages.forReason(InvalidReason.TOO_LOW, 14));
        assertEquals("That card is too high: play 4 or lower.",
                PlayErrorMessages.forReason(InvalidReason.TOO_HIGH, 4));
        assertEquals("That card is too high: play Queen or lower.",
                PlayErrorMessages.forReason(InvalidReason.TOO_HIGH, 12));
    }

    @Test
    void nullReason_fallsBackToGenericText() {
        // Given / When / Then
        assertEquals("That play can't be made right now.", PlayErrorMessages.forReason(null, 0));
    }

    @Test
    void messages_areDistinctForEveryReason() {
        // Given — every reason with a fixed value
        // When
        long distinct = java.util.Arrays.stream(InvalidReason.values())
                .map(reason -> PlayErrorMessages.forReason(reason, 9))
                .distinct()
                .count();

        // Then — the messages differ from one another, and none is the generic fallback
        assertEquals(InvalidReason.values().length, distinct);
        assertFalse(java.util.Arrays.stream(InvalidReason.values())
                .anyMatch(reason -> PlayErrorMessages.forReason(reason, 9).equals("That play can't be made right now.")));
    }
}
