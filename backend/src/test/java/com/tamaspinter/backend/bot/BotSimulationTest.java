package com.tamaspinter.backend.bot;

import com.tamaspinter.backend.game.CardSelection;
import com.tamaspinter.backend.game.CardSource;
import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.game.PlayResult;
import com.tamaspinter.backend.mapper.SessionMapper;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.model.Player;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

import static com.tamaspinter.backend.bot.BotTables.activeCount;
import static com.tamaspinter.backend.bot.BotTables.startDeterministic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Plays a handful of complete, seeded games with beginner bots and scripted humans. Every bot choice is checked
 * against {@link GameSession#legalPlays()} and replayed on a copy of the session before the runner applies it,
 * so an illegal bot move, a stuck bot loop or a game that never ends fails the test.
 */
class BotSimulationTest {

    private static final Logger LOG = LoggerFactory.getLogger(BotSimulationTest.class);
    /** Hard cap on bot plus human moves in one game. */
    private static final int MAX_TURNS_PER_GAME = 5000;

    private record Table(String name, GameConfig config, int humans, int bots, long seed) {
    }

    /** What one game produced. */
    private record Outcome(int botMoves, int humanMoves, boolean stalled) {
    }

    /** The beginner strategy, with every play choice checked before it is returned. */
    private static final class CheckingStrategy implements BotStrategy {
        private final BeginnerBotStrategy inner = new BeginnerBotStrategy();
        private final GameSession session;
        private final List<String> problems = new ArrayList<>();
        private int choices;

        CheckingStrategy(GameSession session) {
            this.session = session;
        }

        @Override
        public SetupSwap chooseSetupSwap(List<Card> hand, List<Card> faceUp, GameConfig config) {
            return inner.chooseSetupSwap(hand, faceUp, config);
        }

        @Override
        public List<CardSelection> choosePlay(BotView view, List<List<CardSelection>> legalPlays, Random random) {
            choices++;
            List<CardSelection> choice = inner.choosePlay(view, legalPlays, random);
            String where = "move " + choices + " by " + view.playerId() + ": ";
            if (!legalPlays.equals(session.legalPlays())) {
                problems.add(where + "runner passed stale legal plays");
            }
            if (choice != null && !legalPlays.contains(choice)) {
                problems.add(where + "choice " + choice + " is not a legal play");
            }
            if (choice == null && (!legalPlays.isEmpty() || session.getDiscardPile().isEmpty())) {
                problems.add(where + "picked up although it could play");
            }
            GameSession copy = SessionMapper.fromEntity(SessionMapper.toEntity(session));
            PlayResult result = choice == null ? copy.pickupPile() : copy.playSelections(choice);
            if (result == PlayResult.INVALID) {
                problems.add(where + "choice " + choice + " rejected: " + copy.getLastInvalidReason());
            }
            boolean blindFlip = choice != null && choice.get(0).source() == CardSource.FACE_DOWN;
            if (choice != null && !blindFlip && result != PlayResult.SUCCESS) {
                problems.add(where + "visible play " + choice + " did not succeed: " + result);
            }
            return choice;
        }
    }

    // ---------------------------------------------------------------- configs

    private static GameConfig withRules(GameConfig.GameConfigBuilder builder, GameConfig rules) {
        return builder
                .cardRuleMap(new HashMap<>(rules.getCardRuleMap()))
                .alwaysPlayableMap(new HashMap<>(rules.getAlwaysPlayableMap()))
                .canPlayAgainMap(new HashMap<>(rules.getCanPlayAgainMap()))
                .build();
    }

    private static GameConfig defaults() {
        return GameConfig.defaultGameConfig();
    }

    /** 2 face down, 2 face up, 4 in hand (six seats on one deck) and a reshuffled set of special ranks. */
    private static GameConfig custom() {
        Map<Integer, CardRule> rules = new HashMap<>();
        rules.put(3, CardRule.BURNER);
        rules.put(5, CardRule.JOKER);
        rules.put(7, CardRule.SMALLER);
        rules.put(11, CardRule.TRANSPARENT);
        rules.put(12, CardRule.REVERSE);
        Map<Integer, Boolean> always = new HashMap<>();
        always.put(5, true);
        always.put(11, true);
        Map<Integer, Boolean> again = new HashMap<>();
        again.put(3, true);
        return GameConfig.builder()
                .faceDownCount(2)
                .faceUpCount(2)
                .handCount(4)
                .burnCount(4)
                .cardRuleMap(rules)
                .alwaysPlayableMap(always)
                .canPlayAgainMap(again)
                .build();
    }

    private static GameConfig mixedAndFailedFaceUp(int decks) {
        return withRules(GameConfig.builder()
                .decksCount(decks)
                .faceDownCount(3)
                .faceUpCount(3)
                .handCount(3)
                .burnCount(decks == 1 ? 4 : 6)
                .allowMixedHandAndFaceUpWhenDeckEmpty(true)
                .allowFailedFaceUpPlay(true), defaults());
    }

    private static GameConfig twoDecks() {
        return withRules(GameConfig.builder()
                .decksCount(2)
                .faceDownCount(3)
                .faceUpCount(3)
                .handCount(3)
                .burnCount(6), defaults());
    }

    private static List<Table> tables() {
        return List.of(
                new Table("2 bots, default", defaults(), 0, 2, 101),
                new Table("3 bots, default", defaults(), 0, 3, 102),
                new Table("4 bots, custom", custom(), 0, 4, 103),
                new Table("5 bots, mixed + failed face-up", mixedAndFailedFaceUp(1), 0, 5, 104),
                new Table("6 bots, custom", custom(), 0, 6, 105),
                new Table("1 human + 1 bot, default", defaults(), 1, 1, 106),
                new Table("2 humans + 2 bots, mixed + failed face-up", mixedAndFailedFaceUp(1), 2, 2, 107),
                new Table("1 human + 5 bots, custom", custom(), 1, 5, 108),
                new Table("3 humans + 6 bots, two decks", twoDecks(), 3, 6, 109),
                new Table("10 bots, two decks, mixed + failed face-up", mixedAndFailedFaceUp(2), 0, 10, 110));
    }

    // ---------------------------------------------------------------- scripted humans

    /** A random legal play, sometimes a pickup, and with the option on sometimes a deliberately illegal face-up card. */
    private static PlayResult humanMove(GameSession session, Random random) {
        Player player = session.getCurrentPlayer();
        List<List<CardSelection>> legalPlays = session.legalPlays();
        boolean pileHasCards = !session.getDiscardPile().isEmpty();
        if (legalPlays.isEmpty() || pileHasCards && random.nextInt(8) == 0) {
            assertTrue(pileHasCards, "a human with no legal play must face a pile");
            return session.pickupPile();
        }
        boolean faceUpTurn = player.getHand().isEmpty() && !player.getFaceUp().isEmpty();
        if (faceUpTurn && pileHasCards && session.getConfig().isAllowFailedFaceUpPlay() && random.nextInt(4) == 0) {
            int index = random.nextInt(player.getFaceUp().size());
            return session.playSelections(List.of(new CardSelection(CardSource.FACE_UP, index)));
        }
        return session.playSelections(legalPlays.get(random.nextInt(legalPlays.size())));
    }

    private static void setUpHumans(GameSession session) {
        for (Player player : session.getPlayers()) {
            if (!player.isBot()) {
                session.swapStartingCards(player.getPlayerId(), 0, 0);
                assertTrue(session.markReady(player.getPlayerId()));
            }
        }
    }

    // ---------------------------------------------------------------- one game

    private static GameSession seat(Table table) {
        GameSession session = GameSession.builder().sessionId(table.name()).ownerId("human-0")
                .config(table.config()).build();
        int humans = 0;
        int bots = 0;
        while (humans < table.humans() || bots < table.bots()) {
            if (bots < table.bots()) {
                session.addBot(BotType.BEGINNER);
                bots++;
            }
            if (humans < table.humans()) {
                session.addPlayer("human-" + humans, "Human " + humans);
                humans++;
            }
        }
        return session;
    }

    private static Outcome playGame(Table table) {
        GameSession session = seat(table);
        startDeterministic(session, table.seed());
        Random random = new Random(table.seed());
        CheckingStrategy strategy = new CheckingStrategy(session);
        Function<BotType, BotStrategy> strategies = type -> strategy;

        BotTurnRunner.completeSetup(session, strategies);
        setUpHumans(session);
        assertTrue(session.isSetupComplete(), table.name());

        int botMoves = 0;
        int humanMoves = 0;
        while (!session.isFinished() && botMoves + humanMoves < MAX_TURNS_PER_GAME) {
            Player current = session.getCurrentPlayer();
            assertNotNull(current, table.name());
            assertFalse(current.isOut(), table.name() + ": an out player is to move");
            if (current.isBot()) {
                int moves = BotTurnRunner.playBotTurns(session, random, strategies);
                assertTrue(moves > 0, table.name() + ": bot loop made no move");
                botMoves += moves;
                assertTrue(session.isFinished() || !session.getCurrentPlayer().isBot(),
                        table.name() + ": bot loop stopped on a bot's turn");
            } else {
                assertNotEquals(PlayResult.INVALID, humanMove(session, random),
                        table.name() + ": scripted human move rejected: " + session.getLastInvalidReason());
                humanMoves++;
            }
        }
        assertEquals(List.of(), strategy.problems, table.name());
        assertEquals(botMoves, strategy.choices, table.name() + ": every bot move must come from one checked choice");
        assertTrue(botMoves + humanMoves < MAX_TURNS_PER_GAME, table.name() + ": turn cap reached");
        assertTrue(session.isFinished(), table.name());
        assertNotNull(session.getShitheadId(), table.name());
        boolean stalled = activeCount(session) != 1;
        if (!stalled) {
            Player shithead = session.getPlayers().stream().filter(player -> !player.isOut()).findFirst().orElseThrow();
            assertEquals(shithead.getPlayerId(), session.getShitheadId(), table.name());
        }
        return new Outcome(botMoves, humanMoves, stalled);
    }

    @Test
    void simulation_seededTables_everyGameEndsWithLegalBotMovesAndOneShithead() {
        // Given
        List<Table> tables = tables();
        int botMoves = 0;
        int humanMoves = 0;
        int stalled = 0;

        // When
        for (Table table : tables) {
            Outcome outcome = playGame(table);
            botMoves += outcome.botMoves();
            humanMoves += outcome.humanMoves();
            stalled += outcome.stalled() ? 1 : 0;
        }

        // Then
        LOG.info("Bot simulation: {} games, {} bot moves, {} human moves, {} stalled finishes",
                tables.size(), botMoves, humanMoves, stalled);
        assertTrue(botMoves > 0);
        assertTrue(humanMoves > 0);
        assertEquals(0, stalled, "games ended by finishStalledBotGame");
    }

    @Test
    void simulation_sameSeed_replaysIdentically() {
        // Given
        Table table = tables().get(7);

        // When
        Outcome first = playGame(table);
        Outcome second = playGame(table);

        // Then
        assertEquals(first, second);
    }

    @Test
    void simulation_strategyThatAlwaysThrows_stillFinishesGame() {
        // Given
        Table table = new Table("4 bots, throwing strategy", defaults(), 0, 4, 201);
        GameSession session = seat(table);
        startDeterministic(session, table.seed());
        BotStrategy broken = new BotStrategy() {
            @Override
            public SetupSwap chooseSetupSwap(List<Card> hand, List<Card> faceUp, GameConfig config) {
                throw new IllegalStateException("broken");
            }

            @Override
            public List<CardSelection> choosePlay(BotView view, List<List<CardSelection>> legalPlays, Random random) {
                throw new IllegalStateException("broken");
            }
        };

        // When
        BotTurnRunner.completeSetup(session, type -> broken);
        int moves = BotTurnRunner.playBotTurns(session, new Random(1), type -> broken);

        // Then
        if (!session.isFinished()) {
            fail("game did not finish");
        }
        assertTrue(moves > 0);
        assertNotNull(session.getShitheadId());
    }
}
