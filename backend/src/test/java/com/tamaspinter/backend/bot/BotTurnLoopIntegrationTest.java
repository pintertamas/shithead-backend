package com.tamaspinter.backend.bot;

import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.game.CardSelection;
import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.game.PlayResult;
import com.tamaspinter.backend.mapper.SessionMapper;
import com.tamaspinter.backend.model.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The WebSocket handlers cannot run without AWS (the user id comes from a DynamoDB connection row), so this
 * drives the same steps the handler's save-after-move does: load the entity, apply the human move, let the bots
 * play in memory, store the entity again.
 */
class BotTurnLoopIntegrationTest {

    private static final String HUMAN = "human-1";
    private static final int GAMES = 25;
    private static final int MAX_HUMAN_MOVES = 3000;

    @Test
    void turnLoop_humanMovesThroughEntityRoundTrips_botsAnswerUntilHumanTurnOrFinish() {
        int finished = 0;
        for (int game = 0; game < GAMES; game++) {
            // Given: a started game with one human and two bots, setup done
            GameSessionEntity stored = startedSession().toEntity();
            Random random = new Random(game);

            int humanMoves = 0;
            while (!stored.isFinished() && humanMoves++ < MAX_HUMAN_MOVES) {
                // When: the human plays on a freshly loaded session, then the bots run, then it is stored
                GameSession session = SessionMapper.fromEntity(stored);
                if (HUMAN.equals(session.getCurrentPlayerId())) {
                    humanMove(session, random);
                }
                BotTurnRunner.playBotTurns(session);
                stored = session.toEntity();

                // Then: after the bots ran it is a living human's turn, or the game is over
                GameSession reloaded = SessionMapper.fromEntity(stored);
                Player current = reloaded.getCurrentPlayer();
                assertTrue(reloaded.isFinished() || !current.isBot() && !current.isOut(),
                        "bots left the turn on a bot or a finished player");
            }
            if (stored.isFinished()) {
                finished++;
                assertNotNull(stored.getShitheadId());
            }
        }
        // A random human can stall a two-player endgame by trading the pile back and forth; most games end.
        assertTrue(finished > GAMES / 2, "only " + finished + " games finished");
    }

    @Test
    void turnLoop_humanGoesOut_botsFinishTheGameInTheSameInvocation() {
        for (int game = 0; game < GAMES; game++) {
            // Given: a started game; the human has already gone out, so only bots are left
            GameSession session = startedSession();
            Player human = session.getPlayers().stream().filter(player -> !player.isBot()).findFirst().orElseThrow();
            human.getHand().clear();
            human.getFaceUp().clear();
            human.getFaceDown().clear();
            human.setOut(true);
            if (HUMAN.equals(session.getCurrentPlayerId())) {
                session.setCurrentIndex((session.getCurrentIndex() + 1) % session.getPlayers().size());
            }

            // When: the bots run once, as in the handler after the human's last move
            BotTurnRunner.playBotTurns(session);

            // Then: the loop ends the game itself and the shithead is one of the bots
            assertTrue(session.isFinished());
            assertTrue(session.getPlayers().stream()
                    .anyMatch(player -> player.getPlayerId().equals(session.getShitheadId()) && player.isBot()));
        }
    }

    @Test
    void completeSetup_afterStart_marksOnlyBotsReadyAndKeepsSetupOpen() {
        // Given
        GameSession session = GameSession.builder().sessionId("S").ownerId(HUMAN).build();
        session.addPlayer(HUMAN, "human");
        session.addBot(BotType.BEGINNER);
        session.start();

        // When
        BotTurnRunner.completeSetup(session);

        // Then
        assertFalse(session.isSetupComplete());
        for (Player player : session.getPlayers()) {
            assertEquals(player.isBot(), player.isReady());
        }
    }

    private static void humanMove(GameSession session, Random random) {
        List<List<CardSelection>> plays = session.legalPlays();
        PlayResult result = plays.isEmpty() ? PlayResult.INVALID : session.playSelections(plays.get(random.nextInt(plays.size())));
        if (result == PlayResult.INVALID) {
            session.pickupPile();
        }
    }

    private static GameSession startedSession() {
        GameSession session = GameSession.builder()
                .sessionId("S")
                .ownerId(HUMAN)
                .config(GameConfig.defaultGameConfig())
                .build();
        session.addPlayer(HUMAN, "human");
        session.addBot(BotType.BEGINNER);
        session.addBot(BotType.BEGINNER);
        session.start();
        BotTurnRunner.completeSetup(session);
        session.markReady(HUMAN);
        return session;
    }
}
