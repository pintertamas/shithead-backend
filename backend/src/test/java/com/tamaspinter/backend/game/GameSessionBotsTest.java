package com.tamaspinter.backend.game;

import com.tamaspinter.backend.bot.BotType;
import com.tamaspinter.backend.model.Player;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameSessionBotsTest {

    private static final String OWNER = "owner";
    private static final String HUMAN = "human";

    @Test
    void removePlayer_ownerLeavesWithBotAndHuman_humanBecomesOwner() {
        // Given: [owner, bot, human]
        GameSession session = GameSession.builder().sessionId("S").ownerId(OWNER).build();
        session.addPlayer(OWNER, "owner");
        session.addBot(BotType.BEGINNER);
        session.addPlayer(HUMAN, "human");

        // When
        session.removePlayer(OWNER);

        // Then: the bot is skipped as the new owner
        assertEquals(HUMAN, session.getOwnerId());
        assertEquals(2, session.getPlayers().size());
    }

    @Test
    void removePlayer_ownerLeavesWithOnlyABot_emptiesTheLobby() {
        // Given: [owner, bot]
        GameSession session = GameSession.builder().sessionId("S").ownerId(OWNER).build();
        session.addPlayer(OWNER, "owner");
        session.addBot(BotType.BEGINNER);

        // When
        session.removePlayer(OWNER);

        // Then: callers delete a lobby with no players
        assertTrue(session.getPlayers().isEmpty());
    }

    @Test
    void removePlayer_nonOwnerLeaves_ownerStays() {
        // Given
        GameSession session = GameSession.builder().sessionId("S").ownerId(OWNER).build();
        session.addPlayer(OWNER, "owner");
        session.addBot(BotType.BEGINNER);
        session.addPlayer(HUMAN, "human");

        // When
        session.removePlayer(HUMAN);

        // Then
        assertEquals(OWNER, session.getOwnerId());
        assertEquals(List.of(OWNER), session.getPlayers().stream()
                .filter(player -> !player.isBot()).map(Player::getPlayerId).toList());
    }

    @Test
    void addBot_afterStart_isRefused() {
        // Given
        GameSession session = GameSession.builder().sessionId("S").ownerId(OWNER).build();
        session.addPlayer(OWNER, "owner");
        session.addBot(BotType.BEGINNER);
        session.start();

        // When / Then
        assertThrows(IllegalStateException.class, () -> session.addBot(BotType.BEGINNER));
    }
}
