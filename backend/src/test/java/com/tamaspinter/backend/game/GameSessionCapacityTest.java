package com.tamaspinter.backend.game;

import com.tamaspinter.backend.model.Player;
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

    /** Seats players without the join check, so start() can be tested against a table that was over-filled. */
    private static GameSession sessionSeating(int decks, int players) {
        GameSession session = GameSession.builder().sessionId("capacity").config(layout(decks)).build();
        for (int i = 0; i < players; i++) {
            session.getPlayers().add(Player.builder().playerId("p" + i).username("player" + i).build());
        }
        return session;
    }

    @Test
    void seatCapacity_oneDeckWithDefaultLayout_isFive() {
        // Given: one deck, three face-down, three face-up and three hand cards per player -> 52 / 9 = 5
        // When
        int capacity = GameSession.seatCapacity(1, 3, 3, 3);

        // Then
        assertEquals(5, capacity);
    }

    @Test
    void seatCapacity_twoDecksWithDefaultLayout_isTen() {
        // Given: two decks, nine cards per player -> 104 / 9 = 11, capped at the maximum
        // When
        int capacity = GameSession.seatCapacity(2, 3, 3, 3);

        // Then
        assertEquals(10, capacity);
    }

    @Test
    void seatCapacity_largeLayoutOnOneDeck_isTwo() {
        // Given: one deck, 6 + 6 + 6 cards per player -> 52 / 18 = 2
        // When
        int capacity = GameSession.seatCapacity(1, 6, 6, 6);

        // Then
        assertEquals(2, capacity);
    }

    @Test
    void seatCapacity_noCardsPerPlayer_isMaxPlayers() {
        // Given: a layout that deals no cards at all
        // When
        int capacity = GameSession.seatCapacity(1, 0, 0, 0);

        // Then
        assertEquals(GameSession.MAX_PLAYERS, capacity);
    }

    @Test
    void seatCapacity_instance_followsItsOwnDeckCount() {
        // Given: a one-deck and a two-deck game with the default layout
        GameSession oneDeck = sessionWithPlayers(1, 0);
        GameSession twoDecks = sessionWithPlayers(2, 0);

        // When / Then
        assertEquals(5, oneDeck.seatCapacity());
        assertEquals(10, twoDecks.seatCapacity());
    }

    @Test
    void addPlayer_oneDeckWithFourPlayers_acceptsFifthPlayer() {
        // Given: one deck with four seated players, one short of its capacity
        GameSession session = sessionWithPlayers(1, 4);

        // When
        session.addPlayer("p4", "fifth");

        // Then
        assertEquals(5, session.getPlayers().size());
    }

    @Test
    void addPlayer_oneDeckAtFive_rejectsSixthPlayerWithDeckMessage() {
        // Given: one deck with five players, the most it can seat
        GameSession session = sessionWithPlayers(1, 5);

        // When
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> session.addPlayer("p5", "sixth"));

        // Then
        assertEquals("Game is full: one deck seats 5 players. The owner can add a second deck.", error.getMessage());
        assertEquals(5, session.getPlayers().size());
    }

    @Test
    void addPlayer_twoDecksWithSixPlayers_acceptsSeventhPlayer() {
        // Given: two decks with six players, already past the one-deck capacity
        GameSession session = sessionWithPlayers(2, 6);

        // When
        session.addPlayer("p6", "seventh");

        // Then
        assertEquals(7, session.getPlayers().size());
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
        // Given: 10 players x 9 cards = 90 cards, one deck holds 52 (joins would stop at 5, so seat them directly)
        GameSession session = sessionSeating(1, 10);

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
