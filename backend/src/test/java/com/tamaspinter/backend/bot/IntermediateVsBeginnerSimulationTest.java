package com.tamaspinter.backend.bot;

import com.tamaspinter.backend.game.CardSelection;
import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.game.PlayResult;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.Player;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

import static com.tamaspinter.backend.bot.BotTables.startDeterministic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seeded head-to-head games between intermediate and beginner bots (some with a scripted random human), played in
 * the JVM through {@link GameSession} and {@link BotTurnRunner}. Every bot choice is checked against the legal plays.
 * The intermediate bot must end up the shithead clearly less often than the beginner, per seat it occupies.
 */
@Slf4j
class IntermediateVsBeginnerSimulationTest {
    /** Hard cap on bot plus human moves in one game. */
    static final int MAX_TURNS_PER_GAME = 5000;
    private static final BotType BEGINNER = BotType.BEGINNER;
    private static final BotType INTERMEDIATE = BotType.INTERMEDIATE;

    /** One seeded game; a null seat is a scripted human. Seats are listed in turn order, the first one starts. */
    record Table(String name, GameConfig config, List<BotType> seats, long seed) {
    }

    /** Results over many games. Seat counts and shitheads are per bot type (humans are not counted). */
    static final class Tally {
        final Map<BotType, Integer> seats = new EnumMap<>(BotType.class);
        final Map<BotType, Integer> shitheads = new EnumMap<>(BotType.class);
        /** Sum over games of (seats of the type / seats at the table): the shitheads a fair coin would give. */
        final Map<BotType, Double> fairShare = new EnumMap<>(BotType.class);
        final List<String> problems = new ArrayList<>();
        int games;
        int humanShitheads;
        int stalled;
        int turnCapHits;
        int choices;

        double lossRate(BotType type) {
            return shitheads.getOrDefault(type, 0) / (double) Math.max(1, seats.getOrDefault(type, 0));
        }

        /** Shitheads of the type relative to its fair share: 1 is average, below 1 loses less than its share. */
        double relativeLoss(BotType type) {
            return shitheads.getOrDefault(type, 0) / Math.max(1e-9, fairShare.getOrDefault(type, 0.0));
        }

        String summary() {
            return String.format("%d games, %d choices, beginner shitheads %d/%d seats (rate %.3f, %.2f x fair share), "
                    + "intermediate shitheads %d/%d seats (rate %.3f, %.2f x fair share), human shitheads %d, "
                    + "stalled %d, turn cap hits %d", games, choices,
                    shitheads.getOrDefault(BEGINNER, 0), seats.getOrDefault(BEGINNER, 0), lossRate(BEGINNER),
                    relativeLoss(BEGINNER), shitheads.getOrDefault(INTERMEDIATE, 0), seats.getOrDefault(INTERMEDIATE, 0),
                    lossRate(INTERMEDIATE), relativeLoss(INTERMEDIATE), humanShitheads, stalled, turnCapHits);
        }
    }

    /** Wraps a strategy and records every choice that is not one of the legal plays, or a needless pickup. */
    private static final class Checking implements BotStrategy {
        private final BotStrategy inner;
        private final Tally tally;

        Checking(BotStrategy inner, Tally tally) {
            this.inner = inner;
            this.tally = tally;
        }

        @Override
        public SetupSwap chooseSetupSwap(List<Card> hand, List<Card> faceUp, GameConfig config) {
            return inner.chooseSetupSwap(hand, faceUp, config);
        }

        @Override
        public List<CardSelection> choosePlay(BotView view, List<List<CardSelection>> legalPlays, Random random) {
            tally.choices++;
            List<CardSelection> choice = inner.choosePlay(view, legalPlays, random);
            if (choice == null ? !legalPlays.isEmpty() : !legalPlays.contains(choice)) {
                tally.problems.add(view.playerId() + " chose " + choice + " from " + legalPlays);
            }
            return choice;
        }
    }

    // ---------------------------------------------------------------- configs

    static GameConfig defaults() {
        return GameConfig.defaultGameConfig();
    }

    static GameConfig twoDecks() {
        GameConfig rules = GameConfig.defaultGameConfig();
        return GameConfig.builder()
                .decksCount(2)
                .faceDownCount(3)
                .faceUpCount(3)
                .handCount(3)
                .burnCount(6)
                .cardRuleMap(new HashMap<>(rules.getCardRuleMap()))
                .alwaysPlayableMap(new HashMap<>(rules.getAlwaysPlayableMap()))
                .canPlayAgainMap(new HashMap<>(rules.getCanPlayAgainMap()))
                .build();
    }

    /** 3 burns, 5 is a joker, 7 smaller, 11 transparent, 12 reverses; 2 down, 2 up, 4 in hand. */
    static GameConfig custom() {
        Map<Integer, com.tamaspinter.backend.model.CardRule> rules = new HashMap<>();
        rules.put(3, com.tamaspinter.backend.model.CardRule.BURNER);
        rules.put(5, com.tamaspinter.backend.model.CardRule.JOKER);
        rules.put(7, com.tamaspinter.backend.model.CardRule.SMALLER);
        rules.put(11, com.tamaspinter.backend.model.CardRule.TRANSPARENT);
        rules.put(12, com.tamaspinter.backend.model.CardRule.REVERSE);
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

    /** Seat layouts of the head-to-head: null is a scripted human. */
    static List<List<BotType>> layouts() {
        List<BotType> withHuman = new ArrayList<>(List.of(INTERMEDIATE, BEGINNER));
        withHuman.add(null);
        return List.of(
                List.of(INTERMEDIATE, BEGINNER),
                List.of(INTERMEDIATE, BEGINNER, INTERMEDIATE, BEGINNER),
                List.of(INTERMEDIATE, BEGINNER, BEGINNER, BEGINNER),
                withHuman);
    }

    /** The seats rotated so that a different seat starts. */
    static List<BotType> rotated(List<BotType> seats, int by) {
        List<BotType> result = new ArrayList<>();
        for (int i = 0; i < seats.size(); i++) {
            result.add(seats.get((i + by) % seats.size()));
        }
        return result;
    }

    // ---------------------------------------------------------------- one game

    private static GameSession seat(Table table) {
        GameSession session = GameSession.builder().sessionId(table.name()).ownerId("human-0")
                .config(table.config()).build();
        int humans = 0;
        for (BotType type : table.seats()) {
            if (type == null) {
                session.addPlayer("human-" + humans, "Human " + humans);
                humans++;
            } else {
                session.addBot(type);
            }
        }
        if (table.seats().contains(INTERMEDIATE)) {
            session.setObserver(new CardMemory());
        }
        return session;
    }

    /** A random legal play, sometimes a pickup while the pile has cards. */
    private static PlayResult humanMove(GameSession session, Random random) {
        List<List<CardSelection>> legalPlays = session.legalPlays();
        boolean pileHasCards = !session.getDiscardPile().isEmpty();
        if (legalPlays.isEmpty() || pileHasCards && random.nextInt(8) == 0) {
            return session.pickupPile();
        }
        return session.playSelections(legalPlays.get(random.nextInt(legalPlays.size())));
    }

    /** Plays one seeded game to the end and adds its result to the tally. */
    static void play(Table table, Function<BotType, BotStrategy> strategies, Tally tally) {
        GameSession session = seat(table);
        startDeterministic(session, table.seed());
        Random random = new Random(table.seed());
        Function<BotType, BotStrategy> checked = type -> new Checking(strategies.apply(type), tally);
        BotTurnRunner.completeSetup(session, checked);
        session.getPlayers().stream().filter(player -> !player.isBot())
                .forEach(player -> session.markReady(player.getPlayerId()));
        int turns = 0;
        while (!session.isFinished() && turns < MAX_TURNS_PER_GAME) {
            if (session.getCurrentPlayer().isBot()) {
                int moves = BotTurnRunner.playBotTurns(session, random, checked);
                assertTrue(moves > 0, table.name() + ": the bot loop made no move");
                turns += moves;
            } else {
                assertNotEquals(PlayResult.INVALID, humanMove(session, random), table.name());
                turns++;
            }
        }
        record(table, session, turns, tally);
    }

    private static void record(Table table, GameSession session, int turns, Tally tally) {
        tally.games++;
        tally.turnCapHits += turns >= MAX_TURNS_PER_GAME ? 1 : 0;
        tally.stalled += session.getPlayers().stream().filter(player -> !player.isOut()).count() == 1 ? 0 : 1;
        for (BotType type : table.seats()) {
            if (type != null) {
                tally.seats.merge(type, 1, Integer::sum);
                tally.fairShare.merge(type, 1.0 / table.seats().size(), Double::sum);
            }
        }
        Player shithead = session.getPlayers().stream()
                .filter(player -> player.getPlayerId().equals(session.getShitheadId())).findFirst().orElse(null);
        if (shithead == null) {
            return;
        }
        if (shithead.isBot()) {
            tally.shitheads.merge(shithead.getBotType(), 1, Integer::sum);
        } else {
            tally.humanShitheads++;
        }
    }

    // ---------------------------------------------------------------- tests

    private static List<Table> headToHead() {
        List<Table> tables = new ArrayList<>();
        List<GameConfig> configs = List.of(defaults(), twoDecks(), custom());
        long seed = 9000;
        for (int round = 0; round < 3; round++) {
            for (List<BotType> layout : layouts()) {
                GameConfig config = configs.get(tables.size() % configs.size());
                tables.add(new Table("h2h " + tables.size(), config, rotated(layout, round), seed++));
                tables.add(new Table("h2h " + tables.size(), config, rotated(layout, round + 1), seed++));
            }
        }
        return tables;
    }

    @Test
    void simulation_intermediateAgainstBeginner_losesClearlyLessOftenPerSeat() {
        // Given
        List<Table> tables = headToHead();
        Tally tally = new Tally();

        // When
        tables.forEach(table -> play(table, BotStrategies::forType, tally));

        // Then
        log.info("Intermediate vs beginner: {}", tally.summary());
        assertEquals(List.of(), tally.problems);
        assertEquals(tables.size(), tally.games);
        assertEquals(0, tally.turnCapHits);
        assertEquals(0, tally.stalled);
        assertTrue(tally.relativeLoss(INTERMEDIATE) < tally.relativeLoss(BEGINNER) * 0.6, tally.summary());
    }

    @Test
    void simulation_allIntermediateAndMixedTables_finishWithLegalMoves() {
        // Given
        List<BotType> withHuman = new ArrayList<>(List.of(INTERMEDIATE, INTERMEDIATE));
        withHuman.add(null);
        List<BotType> mixed = new ArrayList<>(List.of(INTERMEDIATE, BEGINNER, INTERMEDIATE));
        mixed.add(null);
        List<Table> tables = List.of(
                new Table("2 intermediates", defaults(), List.of(INTERMEDIATE, INTERMEDIATE), 9101),
                new Table("5 intermediates, custom", custom(), List.of(INTERMEDIATE, INTERMEDIATE, INTERMEDIATE,
                        INTERMEDIATE, INTERMEDIATE), 9102),
                new Table("6 intermediates, two decks", twoDecks(), List.of(INTERMEDIATE, INTERMEDIATE, INTERMEDIATE,
                        INTERMEDIATE, INTERMEDIATE, INTERMEDIATE), 9103),
                new Table("2 intermediates + human", defaults(), withHuman, 9104),
                new Table("mixed + human, two decks", twoDecks(), mixed, 9105));
        Tally tally = new Tally();

        // When
        tables.forEach(table -> play(table, BotStrategies::forType, tally));

        // Then
        assertEquals(List.of(), tally.problems);
        assertEquals(tables.size(), tally.games);
        assertEquals(0, tally.turnCapHits);
        assertEquals(0, tally.stalled);
        assertTrue(tally.choices > 0);
        assertNotNull(tally.summary());
    }
}
