package com.tamaspinter.backend.mapper;

import com.tamaspinter.backend.bot.BotType;
import com.tamaspinter.backend.bot.CardMemory;
import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.entity.PlayerEntity;
import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.game.PublicMoveObserver;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.Player;
import com.tamaspinter.backend.model.Suit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionMapperBotsTest {

    @Test
    void roundTrip_keepsBotTypeAndLeavesHumansWithoutOne() {
        // Given
        GameSession session = GameSession.builder().sessionId("S").ownerId("p1").build();
        session.addPlayer("p1", "alice");
        Player bot = session.addBot(BotType.BEGINNER);

        // When
        GameSessionEntity entity = SessionMapper.toEntity(session);
        GameSession restored = SessionMapper.fromEntity(entity);

        // Then
        assertNull(entity.getPlayers().get(0).getBotType());
        assertEquals("BEGINNER", entity.getPlayers().get(1).getBotType());
        assertFalse(restored.getPlayers().get(0).isBot());
        assertTrue(restored.getPlayers().get(1).isBot());
        assertEquals(BotType.BEGINNER, restored.getPlayers().get(1).getBotType());
        assertEquals(bot.getPlayerId(), restored.getPlayers().get(1).getPlayerId());
    }

    @Test
    void fromEntity_itemWithoutBotType_isHuman() {
        // Given: an item written before bots existed
        PlayerEntity old = PlayerEntity.builder().playerId("p1").username("alice")
                .hand(List.of()).faceUp(List.of()).faceDown(List.of()).build();
        GameSessionEntity entity = GameSessionEntity.builder().sessionId("S").players(List.of(old))
                .discardPile(List.of()).build();

        // When
        GameSession restored = SessionMapper.fromEntity(entity);

        // Then
        assertFalse(restored.getPlayers().get(0).isBot());
        assertNull(restored.getPlayers().get(0).getBotType());
    }

    @Test
    void fromEntity_unknownBotType_isHuman() {
        // Given
        PlayerEntity odd = PlayerEntity.builder().playerId("p1").username("alice").botType("RETIRED")
                .hand(List.of()).faceUp(List.of()).faceDown(List.of()).build();
        GameSessionEntity entity = GameSessionEntity.builder().sessionId("S").players(List.of(odd))
                .discardPile(List.of()).build();

        // When / Then
        assertFalse(SessionMapper.fromEntity(entity).getPlayers().get(0).isBot());
    }

    private static Card card(int value) {
        GameConfig config = GameConfig.defaultGameConfig();
        return Card.builder().suit(Suit.SPADES).value(value).rule(config.getCardRule(value))
                .alwaysPlayable(config.isAlwaysPlayable(value)).build();
    }

    @Test
    void roundTrip_intermediateBot_persistsAndRestoresCardMemory() {
        // Given
        GameSession session = GameSession.builder().sessionId("S").ownerId("p1").build();
        session.addPlayer("p1", "alice");
        session.addBot(BotType.INTERMEDIATE);
        CardMemory memory = new CardMemory();
        memory.cardsPickedUp(session.getPlayers().get(0), List.of(card(3), card(11), card(11)));
        memory.pileBurned(List.of(card(4), card(10)));
        session.setObserver(memory);

        // When
        GameSessionEntity entity = SessionMapper.toEntity(session);
        GameSession restored = SessionMapper.fromEntity(entity);

        // Then
        assertNotNull(entity.getBotMemory());
        CardMemory restoredMemory = assertInstanceOf(CardMemory.class, restored.getObserver());
        assertEquals(List.of(3, 11, 11), restoredMemory.knownHand("p1"));
        assertEquals(1, restoredMemory.burnedCount(4));
        assertEquals(1, restoredMemory.burnedCount(10));
    }

    @Test
    void fromEntity_intermediateBotWithoutStoredMemory_startsEmptyMemory() {
        // Given
        GameSession session = GameSession.builder().sessionId("S").ownerId("p1").build();
        session.addPlayer("p1", "alice");
        session.addBot(BotType.INTERMEDIATE);

        // When
        GameSession restored = SessionMapper.fromEntity(SessionMapper.toEntity(session));

        // Then
        CardMemory restoredMemory = assertInstanceOf(CardMemory.class, restored.getObserver());
        assertEquals(List.of(), restoredMemory.knownHand("p1"));
    }

    @Test
    void roundTrip_noIntermediateBot_writesNoMemoryAndAttachesNone() {
        // Given
        GameSession session = GameSession.builder().sessionId("S").ownerId("p1").build();
        session.addPlayer("p1", "alice");
        session.addBot(BotType.BEGINNER);

        // When
        GameSessionEntity entity = SessionMapper.toEntity(session);
        GameSession restored = SessionMapper.fromEntity(entity);

        // Then
        assertNull(entity.getBotMemory());
        assertSame(PublicMoveObserver.NONE, restored.getObserver());
    }
}
