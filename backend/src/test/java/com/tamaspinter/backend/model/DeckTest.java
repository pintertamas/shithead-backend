package com.tamaspinter.backend.model;

import com.tamaspinter.backend.game.GameConfig;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

class DeckTest {

    @Test
    void testDeckCreation() {
        // Given
        GameConfig mockConfig = Mockito.mock(GameConfig.class);
        when(mockConfig.getCardRule(anyInt())).thenReturn(CardRule.DEFAULT);
        when(mockConfig.isAlwaysPlayable(anyInt())).thenReturn(false);

        // When
        Deck deck = new Deck(1, mockConfig);

        // Then
        assertFalse(deck.isEmpty());

        // Standard deck should have 52 cards (13 cards * 4 suits)
        int cardCount = 0;
        while (!deck.isEmpty()) {
            Optional<Card> card = deck.draw();
            assertTrue(card.isPresent());
            cardCount++;
        }
        assertEquals(52, cardCount);
    }

    @Test
    void testMultipleDecks() {
        // Given
        GameConfig mockConfig = Mockito.mock(GameConfig.class);
        when(mockConfig.getCardRule(anyInt())).thenReturn(CardRule.DEFAULT);
        when(mockConfig.isAlwaysPlayable(anyInt())).thenReturn(false);

        // When
        Deck deck = new Deck(2, mockConfig);

        // Then
        int cardCount = 0;
        while (!deck.isEmpty()) {
            Optional<Card> card = deck.draw();
            assertTrue(card.isPresent());
            cardCount++;
        }
        assertEquals(104, cardCount); // 2 decks * 52 cards
    }

    @Test
    void testEmptyDeck() {
        // Given
        GameConfig mockConfig = Mockito.mock(GameConfig.class);
        when(mockConfig.getCardRule(anyInt())).thenReturn(CardRule.DEFAULT);
        when(mockConfig.isAlwaysPlayable(anyInt())).thenReturn(false);
        Deck deck = new Deck(1, mockConfig);

        // When
        while (!deck.isEmpty()) {
            deck.draw();
        }

        // Then
        assertTrue(deck.isEmpty());
        assertEquals(Optional.empty(), deck.draw());
    }
}
