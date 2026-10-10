package com.tamaspinter.backend.bot;

import com.tamaspinter.backend.game.CardSelection;
import com.tamaspinter.backend.game.CardSource;
import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.function.Function;

import static com.tamaspinter.backend.bot.BotTables.activeCount;
import static com.tamaspinter.backend.bot.BotTables.bot;
import static com.tamaspinter.backend.bot.BotTables.give;
import static com.tamaspinter.backend.bot.BotTables.human;
import static com.tamaspinter.backend.bot.BotTables.pile;
import static com.tamaspinter.backend.bot.BotTables.running;
import static com.tamaspinter.backend.bot.BotTables.startDeterministic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotTurnRunnerTest {

    private static final GameConfig DEFAULTS = GameConfig.defaultGameConfig();

    private static List<Integer> values(java.util.Collection<Card> cards) {
        return cards.stream().map(Card::getValue).toList();
    }

    private static int play(GameSession session) {
        return BotTurnRunner.playBotTurns(session, new Random(1));
    }

    private static Function<BotType, BotStrategy> always(BotStrategy strategy) {
        return type -> strategy;
    }

    /** A strategy whose play choice is fixed; setup keeps the deal. */
    private static BotStrategy choosing(List<CardSelection> choice) {
        return new BotStrategy() {
            @Override
            public SetupSwap chooseSetupSwap(List<Card> hand, List<Card> faceUp, GameConfig config) {
                return SetupSwap.none();
            }

            @Override
            public List<CardSelection> choosePlay(BotView view, List<List<CardSelection>> legalPlays, Random random) {
                return choice;
            }
        };
    }

    private static BotStrategy throwing() {
        return new BotStrategy() {
            @Override
            public SetupSwap chooseSetupSwap(List<Card> hand, List<Card> faceUp, GameConfig config) {
                throw new IllegalStateException("broken setup");
            }

            @Override
            public List<CardSelection> choosePlay(BotView view, List<List<CardSelection>> legalPlays, Random random) {
                throw new IllegalStateException("broken play");
            }
        };
    }

    private static GameSession lobbyWithBots(int bots, boolean withHuman) {
        GameSession session = GameSession.builder().sessionId("setup").ownerId("h1").build();
        if (withHuman) {
            session.addPlayer("h1", "alice");
        }
        for (int i = 0; i < bots; i++) {
            session.addBot(BotType.BEGINNER);
        }
        return session;
    }

    // ---------------------------------------------------------------- setup

    @Test
    void completeSetup_botsAndHuman_readiesOnlyBots() {
        // Given
        GameSession session = lobbyWithBots(2, true);
        startDeterministic(session, 7);

        // When
        BotTurnRunner.completeSetup(session);

        // Then
        assertFalse(session.getPlayers().get(0).isReady());
        assertTrue(session.getPlayers().get(1).isReady());
        assertTrue(session.getPlayers().get(2).isReady());
        assertFalse(session.isSetupComplete());
        assertTrue(session.markReady("h1"));
        assertTrue(session.isSetupComplete());
    }

    @Test
    void completeSetup_bots_endWithTheirStrongestCardsFaceUp() {
        // Given
        GameSession session = lobbyWithBots(4, false);
        startDeterministic(session, 11);

        // When
        BotTurnRunner.completeSetup(session);

        // Then
        assertTrue(session.isSetupComplete());
        BeginnerBotStrategy strategy = new BeginnerBotStrategy();
        for (Player player : session.getPlayers()) {
            SetupSwap again = strategy.chooseSetupSwap(List.copyOf(player.getHand()), List.copyOf(player.getFaceUp()),
                    session.getConfig());
            assertTrue(again.isEmpty(), player.getUsername());
            assertEquals(3, player.getHand().size());
            assertEquals(3, player.getFaceUp().size());
        }
    }

    @Test
    void completeSetup_notStarted_doesNothing() {
        // Given
        GameSession session = lobbyWithBots(2, true);

        // When
        BotTurnRunner.completeSetup(session);

        // Then
        assertFalse(session.isStarted());
        assertTrue(session.getEvents().isEmpty());
    }

    @Test
    void completeSetup_throwingStrategy_keepsDealAndMarksReady() {
        // Given
        GameSession session = lobbyWithBots(2, false);
        startDeterministic(session, 3);
        List<Integer> dealtFaceUp = values(session.getPlayers().get(0).getFaceUp());

        // When
        BotTurnRunner.completeSetup(session, always(throwing()));

        // Then
        assertTrue(session.isSetupComplete());
        assertEquals(dealtFaceUp, values(session.getPlayers().get(0).getFaceUp()));
    }

    // ---------------------------------------------------------------- when the loop runs

    @Test
    void playBotTurns_twoBotsBeforeHuman_stopsAtHuman() {
        // Given
        Player first = bot("b1");
        Player second = bot("b2");
        Player alice = human("h1");
        GameSession session = running(DEFAULTS, first, second, alice);
        give(session, first, 4, 12);
        give(session, second, 5, 13);
        give(session, alice, 14);
        session.setDeck(BotTables.shuffledDeck(DEFAULTS, 1));

        // When
        int moves = play(session);

        // Then
        assertEquals(2, moves);
        assertEquals("h1", session.getCurrentPlayerId());
        assertEquals(List.of(4, 5), values(session.getDiscardPile()));
    }

    @Test
    void playBotTurns_duringSetup_doesNothing() {
        // Given
        GameSession session = lobbyWithBots(3, false);
        startDeterministic(session, 5);

        // When
        int moves = play(session);

        // Then
        assertEquals(0, moves);
        assertTrue(session.getDiscardPile().isEmpty());
    }

    @Test
    void playBotTurns_gameFinished_doesNothing() {
        // Given
        Player first = bot("b1");
        GameSession session = running(DEFAULTS, first, bot("b2"));
        give(session, first, 4);
        session.setFinished(true);

        // When
        int moves = play(session);

        // Then
        assertEquals(0, moves);
    }

    @Test
    void playBotTurns_humanTurn_doesNothing() {
        // Given
        Player alice = human("h1");
        Player other = bot("b1");
        GameSession session = running(DEFAULTS, alice, other);
        give(session, alice, 4);
        give(session, other, 5);

        // When
        int moves = play(session);

        // Then
        assertEquals(0, moves);
        assertEquals("h1", session.getCurrentPlayerId());
    }

    // ---------------------------------------------------------------- moves

    @Test
    void playBotTurns_botCannotPlay_picksUpPile() {
        // Given
        Player first = bot("b1");
        Player alice = human("h1");
        GameSession session = running(DEFAULTS, first, alice);
        give(session, first, 3);
        give(session, alice, 4);
        pile(session, 12, 13);

        // When
        int moves = play(session);

        // Then
        assertEquals(1, moves);
        assertTrue(session.getDiscardPile().isEmpty());
        assertEquals(List.of(3, 12, 13), values(first.getHand()));
        assertEquals("h1", session.getCurrentPlayerId());
    }

    @Test
    void playBotTurns_burnerPlayed_botPlaysAgain() {
        // Given
        Player first = bot("b1");
        Player alice = human("h1");
        GameSession session = running(DEFAULTS, first, alice);
        give(session, first, 5, 10);
        give(session, alice, 4);
        pile(session, 7);

        // When
        int moves = play(session);

        // Then
        assertEquals(2, moves);
        assertEquals(List.of(5), values(session.getDiscardPile()));
        assertTrue(first.isOut());
        assertTrue(session.isFinished());
        assertEquals("h1", session.getShitheadId());
    }

    @Test
    void playBotTurns_fourOfAKindCompleted_burnsAndPlaysAgain() {
        // Given
        Player first = bot("b1");
        Player alice = human("h1");
        GameSession session = running(DEFAULTS, first, alice);
        give(session, first, 7);
        first.getFaceUp().add(BotTables.card(DEFAULTS, 13));
        give(session, alice, 4);
        pile(session, 7, 7, 7);

        // When
        int moves = play(session);

        // Then
        assertEquals(2, moves);
        assertEquals(List.of(13), values(session.getDiscardPile()));
        assertTrue(first.isOut());
        assertEquals("h1", session.getShitheadId());
    }

    @Test
    void playBotTurns_reverse_passesTurnBackToPreviousPlayer() {
        // Given
        Player alice = human("h1");
        Player first = bot("b1");
        Player carol = human("h2");
        GameSession session = running(DEFAULTS, alice, first, carol);
        session.setCurrentIndex(1);
        give(session, alice, 14);
        give(session, first, 3, 9);
        give(session, carol, 14);
        pile(session, 5);

        // When
        int moves = play(session);

        // Then
        assertEquals(1, moves);
        assertEquals("h1", session.getCurrentPlayerId());
        assertEquals(List.of("h2", "b1", "h1"), session.getPlayers().stream().map(Player::getPlayerId).toList());
    }

    @Test
    void playBotTurns_nextBotIsOut_skipsIt() {
        // Given
        Player first = bot("b1");
        Player gone = bot("b2");
        Player alice = human("h1");
        GameSession session = running(DEFAULTS, first, gone, alice);
        gone.setOut(true);
        give(session, first, 4, 12);
        give(session, alice, 14);

        // When
        int moves = play(session);

        // Then
        assertEquals(1, moves);
        assertEquals("h1", session.getCurrentPlayerId());
    }

    @Test
    void playBotTurns_onlyBotsLeftAfterHumanIsOut_playToTheEnd() {
        // Given
        Player alice = human("h1");
        Player first = bot("b1");
        Player second = bot("b2");
        GameSession session = running(DEFAULTS, first, second, alice);
        alice.setOut(true);
        give(session, first, 3, 4, 5, 12);
        give(session, second, 6, 7, 13, 14);

        // When
        int moves = play(session);

        // Then
        assertTrue(moves > 0);
        assertTrue(session.isFinished());
        assertEquals(1, activeCount(session));
        assertFalse(session.getPlayers().stream().filter(p -> !p.isOut()).findFirst().orElseThrow().getPlayerId()
                .equals("h1"));
    }

    // ---------------------------------------------------------------- broken strategies

    @Test
    void playBotTurns_throwingStrategy_makesFirstLegalPlay() {
        // Given
        Player first = bot("b1");
        Player alice = human("h1");
        GameSession session = running(DEFAULTS, first, alice);
        give(session, first, 6, 12);
        give(session, alice, 14);
        pile(session, 5);

        // When
        int moves = BotTurnRunner.playBotTurns(session, new Random(1), always(throwing()));

        // Then
        assertEquals(1, moves);
        assertEquals(List.of(5, 6), values(session.getDiscardPile()));
        assertEquals("h1", session.getCurrentPlayerId());
    }

    @Test
    void playBotTurns_throwingStrategyAndNothingFits_picksUp() {
        // Given
        Player first = bot("b1");
        Player alice = human("h1");
        GameSession session = running(DEFAULTS, first, alice);
        give(session, first, 3);
        give(session, alice, 14);
        pile(session, 13);

        // When
        int moves = BotTurnRunner.playBotTurns(session, new Random(1), always(throwing()));

        // Then
        assertEquals(1, moves);
        assertTrue(session.getDiscardPile().isEmpty());
        assertEquals(List.of(3, 13), values(first.getHand()));
    }

    @Test
    void playBotTurns_choiceNotAmongLegalPlays_makesFirstLegalPlay() {
        // Given
        Player first = bot("b1");
        Player alice = human("h1");
        GameSession session = running(DEFAULTS, first, alice);
        give(session, first, 3, 12);
        give(session, alice, 14);
        pile(session, 5);
        BotStrategy illegal = choosing(List.of(new CardSelection(CardSource.HAND, 0)));

        // When
        int moves = BotTurnRunner.playBotTurns(session, new Random(1), always(illegal));

        // Then
        assertEquals(1, moves);
        assertEquals(List.of(5, 12), values(session.getDiscardPile()));
        assertEquals(List.of(3), values(first.getHand()));
    }

    @Test
    void playBotTurns_choiceOutOfRange_makesFirstLegalPlay() {
        // Given
        Player first = bot("b1");
        Player alice = human("h1");
        GameSession session = running(DEFAULTS, first, alice);
        give(session, first, 12);
        give(session, alice, 14);
        BotStrategy illegal = choosing(List.of(new CardSelection(CardSource.FACE_DOWN, 9)));

        // When
        int moves = BotTurnRunner.playBotTurns(session, new Random(1), always(illegal));

        // Then
        assertEquals(1, moves);
        assertEquals(List.of(12), values(session.getDiscardPile()));
    }

    @Test
    void playBotTurns_pickupChosenOnEmptyPile_playsInstead() {
        // Given
        Player first = bot("b1");
        Player alice = human("h1");
        GameSession session = running(DEFAULTS, first, alice);
        give(session, first, 4, 12);
        give(session, alice, 14);
        BotStrategy pickup = choosing(null);

        // When
        int moves = BotTurnRunner.playBotTurns(session, new Random(1), always(pickup));

        // Then
        assertEquals(1, moves);
        assertEquals(List.of(4), values(session.getDiscardPile()));
        assertEquals("h1", session.getCurrentPlayerId());
    }

    @Test
    void playBotTurns_pickupChosenWhileAbleToPlay_isAllowed() {
        // Given
        Player first = bot("b1");
        Player alice = human("h1");
        GameSession session = running(DEFAULTS, first, alice);
        give(session, first, 12);
        give(session, alice, 14);
        pile(session, 4);
        BotStrategy pickup = choosing(null);

        // When
        int moves = BotTurnRunner.playBotTurns(session, new Random(1), always(pickup));

        // Then
        assertEquals(1, moves);
        assertEquals(List.of(4, 12), values(first.getHand()));
    }

    // ---------------------------------------------------------------- whole games and stalls

    @Test
    void playBotTurns_allBotTables_finishWithinCap() {
        for (long seed = 1; seed <= 20; seed++) {
            // Given
            GameSession session = lobbyWithBots(2 + (int) (seed % 4), false);
            startDeterministic(session, seed);
            BotTurnRunner.completeSetup(session);

            // When
            int moves = BotTurnRunner.playBotTurns(session, new Random(seed));

            // Then
            String context = "seed " + seed;
            assertTrue(session.isFinished(), context);
            assertTrue(moves < BotTurnRunner.MAX_BOT_MOVES, context + " moves " + moves);
            assertEquals(1, activeCount(session), context);
        }
    }

    @Test
    void finishStalledBotGame_onlyBotsActive_botWithMostCardsIsShithead() {
        // Given
        Player alice = human("h1");
        Player first = bot("b1");
        Player second = bot("b2");
        GameSession session = running(DEFAULTS, alice, first, second);
        alice.setOut(true);
        give(session, first, 3, 4);
        give(session, second, 3, 4, 5);
        second.getFaceDown().add(BotTables.card(DEFAULTS, 9));

        // When
        session.finishStalledBotGame();

        // Then
        assertTrue(session.isFinished());
        assertEquals("b2", session.getShitheadId());
    }

    @Test
    void finishStalledBotGame_humanStillPlaying_doesNothing() {
        // Given
        Player alice = human("h1");
        Player first = bot("b1");
        GameSession session = running(DEFAULTS, alice, first);
        give(session, alice, 3);
        give(session, first, 3, 4);

        // When
        session.finishStalledBotGame();

        // Then
        assertFalse(session.isFinished());
        assertNull(session.getShitheadId());
    }

    @Test
    void finishStalledBotGame_alreadyFinished_keepsResult() {
        // Given
        Player first = bot("b1");
        Player second = bot("b2");
        GameSession session = running(DEFAULTS, first, second);
        give(session, first, 3);
        give(session, second, 3, 4, 5);
        session.setFinished(true);
        session.setShitheadId("b1");

        // When
        session.finishStalledBotGame();

        // Then
        assertEquals("b1", session.getShitheadId());
    }
}
