package com.tamaspinter.backend.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameSessionCapacityTest {

    /** Three face-down, three face-up and three hand cards per player. */
    private static GameConfig layout(int decks) {
        return GameConfig.builder()
                .decksCount(decks)
                .faceDownCount(3)
                .faceUpCount(3)
                .handCount(3)
                .build();
    }

    private static GameSession sessionWithPlayers(int decks, int players) {
        GameSession session = GameSession.builder().sessionId("capacity").config(layout(decks)).build();
        for (int i = 0; i < players; i++) {
            session.addPlayer("p" + i, "player" + i);
        }
        return session;
    }

    @Test
    void tenPlayers_twoDecks_startAndEachGetNineCards() {
        // Given: 10 players x 9 cards = 90 cards, two decks hold 104
        GameSession session = sessionWithPlayers(2, 10);

        // When
        session.start();

        // Then
        assertTrue(session.isStarted());
        assertEquals(10, session.getPlayers().size());
        session.getPlayers().forEach(player -> {
            assertEquals(3, player.getFaceDown().size());
            assertEquals(3, player.getFaceUp().size());
            assertEquals(3, player.getHand().size());
        });
    }

    @Test
    void tenPlayers_oneDeck_failWithClearMessage() {
        // Given: 10 players x 9 cards = 90 cards, one deck holds 52
        GameSession session = sessionWithPlayers(1, 10);

        // When
        IllegalStateException error = assertThrows(IllegalStateException.class, session::start);

        // Then: the message names the supported player count and the deck count
        assertEquals("Not enough cards: this setup supports at most 5 players with 1 deck(s)", error.getMessage());
        assertFalse(session.isStarted());
    }

    @Test
    void eleventhPlayer_isRejectedWithFullMessage() {
        // Given: a full table of 10 players
        GameSession session = sessionWithPlayers(2, GameSession.MAX_PLAYERS);

        // When
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> session.addPlayer("p10", "eleventh"));

        // Then
        assertEquals("Game is full: at most 10 players can join", error.getMessage());
        assertEquals(GameSession.MAX_PLAYERS, session.getPlayers().size());
    }
}
