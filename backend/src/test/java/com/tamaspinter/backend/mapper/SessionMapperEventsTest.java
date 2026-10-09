package com.tamaspinter.backend.mapper;

import com.tamaspinter.backend.entity.GameEventEntity;
import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.game.GameEvent;
import com.tamaspinter.backend.game.GameEventType;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.model.Deck;
import com.tamaspinter.backend.model.Suit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionMapperEventsTest {

    private static Card card(Suit suit, int value) {
        return Card.builder().suit(suit).value(value).rule(CardRule.DEFAULT).alwaysPlayable(false).build();
    }

    @Test
    void toEntity_fromEntity_roundTripsEvents() {
        // Given
        GameSession session = GameSession.builder().sessionId("feed-1").build();
        session.addPlayer("p1", "alice");
        session.addPlayer("p2", "bob");
        session.setStarted(true);
        session.setDeck(new Deck(List.of()));
        Card seven = card(Suit.CLUBS, 7);
        session.getPlayers().get(0).getHand().add(seven);
        session.getPlayers().get(0).getHand().add(card(Suit.HEARTS, 4));
        session.playCards(List.of(seven));

        // When
        GameSessionEntity entity = SessionMapper.toEntity(session);
        GameSession restored = SessionMapper.fromEntity(entity);

        // Then
        assertEquals(1, entity.getEvents().size());
        assertEquals(1, restored.getEvents().size());
        GameEvent event = restored.getEvents().get(0);
        assertEquals(1L, event.seq());
        assertEquals(GameEventType.PLAYED, event.type());
        assertEquals("p1", event.playerId());
        assertEquals("alice", event.username());
        assertEquals(1, event.count());
        assertEquals(Suit.CLUBS, event.cards().get(0).getSuit());
        assertEquals(7, event.cards().get(0).getValue());
        assertEquals(event.ts(), session.getEvents().get(0).ts());
    }

    @Test
    void fromEntity_withoutEventsAttribute_yieldsEmptyFeed() {
        // Given — an item written before the activity feed existed
        GameSessionEntity entity = GameSessionEntity.builder()
                .sessionId("old-1")
                .started(true)
                .setupComplete(true)
                .players(List.of())
                .discardPile(List.of())
                .build();
        assertNull(entity.getEvents());

        // When
        GameSession session = SessionMapper.fromEntity(entity);

        // Then
        assertTrue(session.getEvents().isEmpty());
        assertTrue(SessionMapper.toEntity(session).getEvents().isEmpty());
    }

    @Test
    void entitiesToEvents_withNull_returnsEmptyList() {
        // When
        List<GameEvent> events = SessionMapper.entitiesToEvents(null);

        // Then
        assertTrue(events.isEmpty());
    }

    @Test
    void entitiesToEvents_withNullCards_returnsEventWithNoCards() {
        // Given
        GameEventEntity entity = GameEventEntity.builder()
                .seq(3L)
                .type(GameEventType.PICKED_UP)
                .playerId("p1")
                .username("alice")
                .count(4)
                .ts(99L)
                .build();

        // When
        List<GameEvent> events = SessionMapper.entitiesToEvents(List.of(entity));

        // Then
        assertEquals(1, events.size());
        assertTrue(events.get(0).cards().isEmpty());
        assertEquals(4, events.get(0).count());
    }
}
