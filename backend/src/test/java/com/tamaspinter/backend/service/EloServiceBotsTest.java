package com.tamaspinter.backend.service;

import com.tamaspinter.backend.bot.BotType;
import com.tamaspinter.backend.model.Player;
import com.tamaspinter.backend.service.EloService.EloChange;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EloServiceBotsTest {

    private static final String BEGINNER = EloService.botTypeId(BotType.BEGINNER);
    private static final String INTERMEDIATE = EloService.botTypeId(BotType.INTERMEDIATE);

    private static Player human(String id) {
        return Player.builder().playerId(id).username(id).build();
    }

    private static Player bot(String id, BotType type) {
        return Player.builder().playerId(id).username(id).botType(type).build();
    }

    /** One human plus {@code count} beginner bots named b0..b(count-1). */
    private static List<Player> table(int count) {
        List<Player> players = new ArrayList<>();
        players.add(human("h"));
        for (int i = 0; i < count; i++) {
            players.add(bot("b" + i, BotType.BEGINNER));
        }
        return players;
    }

    @Test
    void rateGame_botTypeIsOneParticipantNoMatterHowManyInstances() {
        // Given: the same human and type ratings, the human lost, at a table with 1 and with 8 instances
        Map<String, Double> ratings = Map.of("h", 1000.0, BEGINNER, 1000.0);

        // When
        Map<String, EloChange> one = EloService.rateGame(table(1), "h", ratings);
        Map<String, EloChange> eight = EloService.rateGame(table(8), "h", ratings);

        // Then: the same per-type and per-human deltas, one entry per participant
        assertEquals(2, eight.size());
        assertEquals(one.get(BEGINNER).after(), eight.get(BEGINNER).after(), 1e-9);
        assertEquals(one.get("h").after(), eight.get("h").after(), 1e-9);
        assertEquals(1016.0, eight.get(BEGINNER).after(), 1e-9);
    }

    @Test
    void rateGame_botInstanceIsShithead_typeLosesOnceAndHumanGainsTheSameAsAgainstOneBot() {
        Map<String, Double> ratings = Map.of("h", 1000.0, BEGINNER, 1000.0);

        Map<String, EloChange> one = EloService.rateGame(table(1), "b0", ratings);
        Map<String, EloChange> eight = EloService.rateGame(table(8), "b3", ratings);

        assertEquals(984.0, eight.get(BEGINNER).after(), 1e-9);
        assertEquals(one.get(BEGINNER).after(), eight.get(BEGINNER).after(), 1e-9);
        assertEquals(one.get("h").after(), eight.get("h").after(), 1e-9);
    }

    @Test
    void rateGame_twoTypesAndTwoHumans_everyParticipantCountsOnce() {
        List<Player> players = List.of(human("h1"), human("h2"), bot("a", BotType.BEGINNER),
                bot("b", BotType.BEGINNER), bot("c", BotType.INTERMEDIATE));
        Map<String, Double> ratings = Map.of("h1", 1000.0, "h2", 1000.0, BEGINNER, 1000.0, INTERMEDIATE, 1000.0);

        Map<String, EloChange> changes = EloService.rateGame(players, "b", ratings);

        assertEquals(4, changes.size());
        assertTrue(changes.get(BEGINNER).after() < 1000.0);
        assertTrue(changes.get(INTERMEDIATE).after() > 1000.0);
        assertTrue(changes.get("h1").after() > 1000.0);
    }

    @Test
    void rateGame_noHuman_ratesNothing() {
        List<Player> players = List.of(bot("a", BotType.BEGINNER), bot("b", BotType.INTERMEDIATE));
        Map<String, Double> ratings = Map.of(BEGINNER, 1000.0, INTERMEDIATE, 1000.0);

        assertTrue(EloService.rateGame(players, "a", ratings).isEmpty());
    }

    @Test
    void rateGame_shitheadNotRated_ratesNothingBecauseNobodyWouldLose() {
        // Given: the human lost but has no profile, so only the bot type is rated
        Map<String, Double> ratings = Map.of(BEGINNER, 1000.0);

        assertTrue(EloService.rateGame(table(2), "h", ratings).isEmpty());
    }

    @Test
    void rateGame_humansOnly_matchesTheClassicUpdate() {
        List<Player> players = List.of(human("a"), human("b"), human("c"));
        Map<String, Double> ratings = Map.of("a", 1200.0, "b", 1000.0, "c", 800.0);

        Map<String, EloChange> changes = EloService.rateGame(players, "c", ratings);
        Map<String, Double> classic = EloService.updateRatings(ratings, Map.of("a", 1.0, "b", 1.0, "c", 0.0));

        classic.forEach((id, after) -> assertEquals(after, changes.get(id).after(), 1e-9));
    }

    @Test
    void farming_humanAlwaysBeatsEightInstances_typeRatingNeverRisesAndHumanGainShrinks() {
        // Given: a human who always wins against 8 beginner instances
        Map<String, Double> ratings = new HashMap<>(Map.of("h", 1000.0, BEGINNER, 1000.0));
        double previousGain = Double.MAX_VALUE;

        // When: 200 games, a different instance is the shithead each time
        for (int game = 0; game < 200; game++) {
            double typeBefore = ratings.get(BEGINNER);
            double humanBefore = ratings.get("h");
            Map<String, EloChange> changes = EloService.rateGame(table(8), "b" + (game % 8), ratings);
            ratings.put(BEGINNER, changes.get(BEGINNER).after());
            ratings.put("h", changes.get("h").after());

            // Then: the type only ever loses points, and each win is worth less to the human than the last
            assertTrue(ratings.get(BEGINNER) < typeBefore, "type must lose when its instance is the shithead");
            double gain = ratings.get("h") - humanBefore;
            assertTrue(gain > 0 && gain < previousGain);
            previousGain = gain;
        }
        // The human's total gain is bounded by one-bot Elo, not multiplied by the 8 instances.
        assertTrue(ratings.get("h") - 1000.0 < 32.0 * 200 / 2);
    }

    @Test
    void farming_typeNeverGainsFromGamesItLost_andEvenSplitStaysBounded() {
        // Given: equal strength, the shithead alternates between the human and a bot instance
        Map<String, Double> ratings = new HashMap<>(Map.of("h", 1000.0, BEGINNER, 1000.0));

        for (int game = 0; game < 1000; game++) {
            boolean botLost = game % 2 == 0;
            double typeBefore = ratings.get(BEGINNER);
            Map<String, EloChange> changes = EloService.rateGame(table(8), botLost ? "b" + (game % 8) : "h", ratings);
            ratings.put(BEGINNER, changes.get(BEGINNER).after());
            ratings.put("h", changes.get("h").after());
            if (botLost) {
                assertTrue(ratings.get(BEGINNER) < typeBefore);
            }
        }

        // Then: with equal win shares the pair stays near its start, it does not drift upward
        assertEquals(1000.0, ratings.get(BEGINNER), 20.0);
        assertEquals(1000.0, ratings.get("h"), 20.0);
    }
}
