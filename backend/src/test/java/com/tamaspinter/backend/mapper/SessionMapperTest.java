package com.tamaspinter.backend.mapper;

import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.entity.PlayerEntity;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.model.Deck;
import com.tamaspinter.backend.model.Player;
import com.tamaspinter.backend.model.Suit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionMapperTest {

    // =========================================================================
    // toEntity
    // =========================================================================

    @Test
    void testToEntity_preservesSessionMetadata() {
        // Given
        GameSession session = new GameSession("session-1");
        session.addPlayer("p1", "alice");
        session.addPlayer("p2", "bob");
        session.setStarted(true);
        session.setFinished(true);
        session.setShitheadId("p2");
        session.setCurrentIndex(1);      // bob's turn
        session.setDeck(new Deck(List.of()));

        // When
        GameSessionEntity entity = SessionMapper.toEntity(session);

        // Then
        assertEquals("session-1", entity.getSessionId());
        assertTrue(entity.isStarted());
        assertTrue(entity.isFinished());
        assertEquals("p2", entity.getShitheadId());
        assertEquals("p2", entity.getCurrentPlayerId()); // index 1 → bob
    }

    @Test
    void testToEntity_preservesPlayerState() {
        // Given
        GameSession session = new GameSession("session-2");
        session.addPlayer("p1", "alice");
        session.setStarted(true);
        session.setDeck(new Deck(List.of()));

        Player alice = session.getPlayers().get(0);
        alice.getHand().add(new Card(Suit.HEARTS, 7, CardRule.DEFAULT, false));
        alice.getHand().add(new Card(Suit.SPADES, 9, CardRule.DEFAULT, false));
        alice.getFaceUp().add(new Card(Suit.CLUBS, 5, CardRule.DEFAULT, false));
        alice.getFaceDown().add(new Card(Suit.DIAMONDS, 3, CardRule.DEFAULT, false));
        alice.setOut(false);

        // When
        GameSessionEntity entity = SessionMapper.toEntity(session);

        // Then
        PlayerEntity pe = entity.getPlayers().get(0);
        assertEquals("p1",   pe.getPlayerId());
        assertEquals("alice", pe.getUsername());
        assertEquals(2, pe.getHand().size());
        assertEquals(1, pe.getFaceUp().size());
        assertEquals(1, pe.getFaceDown().size());
        assertFalse(pe.isOut());
    }

    // =========================================================================
    // fromEntity
    // =========================================================================

    @Test
    void testFromEntity_restoresCurrentIndex() {
        // Given — entity with 3 players; current player is the third one
        GameSessionEntity entity = new GameSessionEntity();
        entity.setSessionId("session-3");
        entity.setCurrentPlayerId("p3");
        entity.setDiscardPile(List.of());
        entity.setDeck(List.of());
        entity.setPlayers(List.of(
                playerEntity("p1", "alice"),
                playerEntity("p2", "bob"),
                playerEntity("p3", "carol")
        ));

        // When
        GameSession session = SessionMapper.fromEntity(entity);

        // Then — currentIndex resolved by matching currentPlayerId
        assertEquals("p3", session.getCurrentPlayerId());
    }

    // =========================================================================
    // Round-trip
    // =========================================================================

    @Test
    void testRoundTrip_preservesGameState() {
        // Given — build a session with known state
        GameSession original = new GameSession("rt-session");
        original.addPlayer("p1", "alice");
        original.addPlayer("p2", "bob");
        original.setStarted(true);
        original.setCurrentIndex(1); // bob's turn

        Player p1 = original.getPlayers().get(0);
        p1.getHand().add(new Card(Suit.HEARTS, 7, CardRule.DEFAULT, false));
        p1.getFaceUp().add(new Card(Suit.CLUBS, 5, CardRule.DEFAULT, false));

        Player p2 = original.getPlayers().get(1);
        p2.setOut(true);

        original.getDiscardPile().add(new Card(Suit.HEARTS, 9, CardRule.DEFAULT, false));
        original.setDeck(new Deck(List.of(new Card(Suit.SPADES, 3, CardRule.DEFAULT, false))));

        // When
        GameSessionEntity entity   = SessionMapper.toEntity(original);
        GameSession        restored = SessionMapper.fromEntity(entity);

        // Then — structural fields survive the round-trip
        assertEquals(original.getSessionId(),       restored.getSessionId());
        assertEquals(original.isStarted(),          restored.isStarted());
        assertEquals(original.getCurrentPlayerId(), restored.getCurrentPlayerId());
        assertEquals(original.getPlayers().size(),  restored.getPlayers().size());

        // Player names and out-status
        assertEquals("alice", restored.getPlayers().get(0).getUsername());
        assertFalse(restored.getPlayers().get(0).isOut());
        assertTrue(restored.getPlayers().get(1).isOut());

        // Card counts per zone
        assertEquals(1, restored.getPlayers().get(0).getHand().size());
        assertEquals(1, restored.getPlayers().get(0).getFaceUp().size());

        // Discard pile — card value preserved (note: suit is not serialised by the mapper)
        assertEquals(1, restored.getDiscardPile().size());
        assertEquals(9, restored.getDiscardPile().peek().getValue());

        // Deck card value preserved
        assertEquals(1, restored.getDeck().getCards().size());
        assertEquals(3, restored.getDeck().getCards().get(0).getValue());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private PlayerEntity playerEntity(String id, String name) {
        PlayerEntity pe = new PlayerEntity();
        pe.setPlayerId(id);
        pe.setUsername(name);
        pe.setHand(List.of());
        pe.setFaceUp(List.of());
        pe.setFaceDown(List.of());
        return pe;
    }
}
