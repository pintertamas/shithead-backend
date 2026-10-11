package com.tamaspinter.backend.game;

import com.tamaspinter.backend.bot.BotType;
import com.tamaspinter.backend.mapper.SessionMapper;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.model.Deck;
import com.tamaspinter.backend.model.Player;
import com.tamaspinter.backend.model.Suit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameSessionLegalPlaysTest {

    private static final GameConfig DEFAULTS = GameConfig.defaultGameConfig();

    // ---------------------------------------------------------------- helpers

    private static Card card(GameConfig config, int value, Suit suit) {
        return Card.builder()
                .suit(suit)
                .value(value)
                .rule(config.getCardRule(value))
                .alwaysPlayable(config.isAlwaysPlayable(value))
                .build();
    }

    private static Card card(int value) {
        return card(DEFAULTS, value, Suit.HEARTS);
    }

    private static GameSession session(GameConfig config) {
        GameSession session = GameSession.builder().sessionId("legal").ownerId("p1").config(config).build();
        session.addPlayer("p1", "alice");
        session.addPlayer("p2", "bob");
        session.setStarted(true);
        session.setSetupComplete(true);
        session.setDeck(new Deck(List.of()));
        session.getPlayers().get(1).getHand().add(card(14));
        return session;
    }

    private static GameSession session() {
        return session(DEFAULTS);
    }

    private static Player alice(GameSession session) {
        return session.getPlayers().get(0);
    }

    private static void hand(GameSession session, int... values) {
        for (int value : values) {
            alice(session).getHand().add(card(session.getConfig(), value, Suit.HEARTS));
        }
        alice(session).sortHand();
    }

    private static void faceUp(GameSession session, int... values) {
        for (int value : values) {
            alice(session).getFaceUp().add(card(session.getConfig(), value, Suit.SPADES));
        }
        alice(session).sortFaceUp();
    }

    private static void faceDown(GameSession session, int... values) {
        for (int value : values) {
            alice(session).getFaceDown().add(card(session.getConfig(), value, Suit.CLUBS));
        }
    }

    private static void pile(GameSession session, int... values) {
        for (int value : values) {
            session.getDiscardPile().addLast(card(session.getConfig(), value, Suit.DIAMONDS));
        }
    }

    private static CardSelection sel(CardSource source, int index) {
        return new CardSelection(source, index);
    }

    private static Card cardAt(Player player, CardSelection selection) {
        List<Card> zone = switch (selection.source()) {
            case HAND -> new ArrayList<>(player.getHand());
            case FACE_UP -> new ArrayList<>(player.getFaceUp());
            case FACE_DOWN -> new ArrayList<>(player.getFaceDown());
        };
        return zone.get(selection.index());
    }

    /** Values of the cards in the listed plays. */
    private static Set<Integer> playableValues(GameSession session) {
        Set<Integer> values = new TreeSet<>();
        Player player = session.getCurrentPlayer();
        session.legalPlays().forEach(play -> play.forEach(selection -> values.add(cardAt(player, selection).getValue())));
        return values;
    }

    private static GameSession copy(GameSession session) {
        return SessionMapper.fromEntity(SessionMapper.toEntity(session));
    }

    private static GameConfig config(boolean mixed, boolean failedFaceUp) {
        return GameConfig.builder()
                .faceDownCount(3)
                .faceUpCount(3)
                .handCount(3)
                .burnCount(4)
                .allowMixedHandAndFaceUpWhenDeckEmpty(mixed)
                .allowFailedFaceUpPlay(failedFaceUp)
                .cardRuleMap(new HashMap<>(DEFAULTS.getCardRuleMap()))
                .alwaysPlayableMap(new HashMap<>(DEFAULTS.getAlwaysPlayableMap()))
                .canPlayAgainMap(new HashMap<>(DEFAULTS.getCanPlayAgainMap()))
                .build();
    }

    /** All ranks DEFAULT except 3 BURNER (plays again), 5 JOKER, 7 SMALLER, 11 TRANSPARENT and 12 REVERSE. */
    private static GameConfig customConfig() {
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
                .faceDownCount(3)
                .faceUpCount(3)
                .handCount(3)
                .burnCount(4)
                .cardRuleMap(rules)
                .alwaysPlayableMap(always)
                .canPlayAgainMap(again)
                .build();
    }

    // ---------------------------------------------------------------- pile tops

    @Test
    void legalPlays_emptyPile_offersEveryHandValue() {
        // Given
        GameSession session = session();
        hand(session, 3, 6, 9, 13);

        // When
        Set<Integer> values = playableValues(session);

        // Then
        assertEquals(Set.of(3, 6, 9, 13), values);
        assertEquals(4, session.legalPlays().size());
    }

    @Test
    void legalPlays_defaultTop_offersEqualOrHigherAndAlwaysPlayable() {
        // Given
        GameSession session = session();
        hand(session, 2, 3, 5, 6, 7, 8, 9, 10, 13);
        pile(session, 7);

        // When
        Set<Integer> values = playableValues(session);

        // Then
        assertEquals(Set.of(2, 7, 8, 9, 10, 13), values);
    }

    @Test
    void legalPlays_smallerTop_offersEqualOrLowerAndAlwaysPlayable() {
        // Given
        GameSession session = session();
        hand(session, 2, 3, 5, 6, 7, 8, 10, 14);
        pile(session, 6);

        // When
        Set<Integer> values = playableValues(session);

        // Then
        assertEquals(Set.of(2, 3, 5, 6, 8), values);
    }

    @Test
    void legalPlays_transparentTopOverDefault_judgesAgainstCardBelow() {
        // Given
        GameSession session = session();
        hand(session, 4, 5, 11, 12);
        pile(session, 11, 8);

        // When
        Set<Integer> values = playableValues(session);

        // Then
        assertEquals(Set.of(11, 12), values);
    }

    @Test
    void legalPlays_transparentTopOverSmaller_needsLowerCard() {
        // Given
        GameSession session = session();
        hand(session, 4, 6, 7, 13);
        pile(session, 6, 8, 8);

        // When
        Set<Integer> values = playableValues(session);

        // Then
        assertEquals(Set.of(4, 6), values);
    }

    @Test
    void legalPlays_allTransparentPile_offersEverything() {
        // Given
        GameSession session = session();
        hand(session, 3, 4, 14);
        pile(session, 8, 8);

        // When
        Set<Integer> values = playableValues(session);

        // Then
        assertEquals(Set.of(3, 4, 14), values);
    }

    @Test
    void legalPlays_jokerTop_offersEverything() {
        // Given
        GameSession session = session();
        hand(session, 3, 4, 14);
        pile(session, 13, 2);

        // When
        Set<Integer> values = playableValues(session);

        // Then
        assertEquals(Set.of(3, 4, 14), values);
    }

    @Test
    void legalPlays_burnerTop_offersEverything() {
        // Given
        GameSession session = session();
        hand(session, 3, 4, 14);
        pile(session, 13, 10);

        // When
        Set<Integer> values = playableValues(session);

        // Then
        assertEquals(Set.of(3, 4, 14), values);
    }

    @Test
    void legalPlays_nothingFits_isEmpty() {
        // Given
        GameSession session = session();
        hand(session, 3, 4, 5);
        pile(session, 13);

        // When
        List<List<CardSelection>> plays = session.legalPlays();

        // Then
        assertTrue(plays.isEmpty());
    }

    // ---------------------------------------------------------------- multiples and zones

    @Test
    void legalPlays_sameValueCards_offersOneToAllOfThemByLowestIndexes() {
        // Given
        GameSession session = session();
        hand(session, 4, 4, 4, 12);

        // When
        List<List<CardSelection>> plays = session.legalPlays();

        // Then
        assertEquals(List.of(
                List.of(sel(CardSource.HAND, 0)),
                List.of(sel(CardSource.HAND, 0), sel(CardSource.HAND, 1)),
                List.of(sel(CardSource.HAND, 0), sel(CardSource.HAND, 1), sel(CardSource.HAND, 2)),
                List.of(sel(CardSource.HAND, 3))), plays);
    }

    @Test
    void legalPlays_handNotEmpty_offersOnlyHandCards() {
        // Given
        GameSession session = session();
        hand(session, 13);
        faceUp(session, 3, 14);
        faceDown(session, 5);
        pile(session, 4);

        // When
        List<List<CardSelection>> plays = session.legalPlays();

        // Then
        assertEquals(List.of(List.of(sel(CardSource.HAND, 0))), plays);
    }

    @Test
    void legalPlays_handEmpty_offersPlayableFaceUpCards() {
        // Given
        GameSession session = session();
        faceUp(session, 5, 9, 9);
        faceDown(session, 3);
        pile(session, 7);

        // When
        List<List<CardSelection>> plays = session.legalPlays();

        // Then
        assertEquals(List.of(
                List.of(sel(CardSource.FACE_UP, 1)),
                List.of(sel(CardSource.FACE_UP, 1), sel(CardSource.FACE_UP, 2))), plays);
    }

    @Test
    void legalPlays_faceUpNothingFits_isEmptyEvenWithFailedFaceUpPlayAllowed() {
        // Given
        GameSession session = session(config(false, true));
        faceUp(session, 3, 4);
        pile(session, 13);

        // When
        List<List<CardSelection>> plays = session.legalPlays();

        // Then
        assertTrue(plays.isEmpty());
    }

    @Test
    void legalPlays_onlyFaceDownLeft_offersEachBlindFlipSeparately() {
        // Given
        GameSession session = session();
        faceDown(session, 3, 4, 5);
        pile(session, 14);

        // When
        List<List<CardSelection>> plays = session.legalPlays();

        // Then
        assertEquals(List.of(
                List.of(sel(CardSource.FACE_DOWN, 0)),
                List.of(sel(CardSource.FACE_DOWN, 1)),
                List.of(sel(CardSource.FACE_DOWN, 2))), plays);
    }

    @Test
    void legalPlays_mixedOptionOnAndDeckEmpty_addsHandPlusFaceUpCombination() {
        // Given
        GameSession session = session(config(true, false));
        hand(session, 5, 9);
        faceUp(session, 5, 12);
        pile(session, 4);

        // When
        List<List<CardSelection>> plays = session.legalPlays();

        // Then
        assertEquals(List.of(
                List.of(sel(CardSource.HAND, 0)),
                List.of(sel(CardSource.HAND, 1)),
                List.of(sel(CardSource.HAND, 0), sel(CardSource.FACE_UP, 0))), plays);
        assertEquals(PlayResult.SUCCESS, copy(session).playSelections(plays.get(2)));
    }

    @Test
    void legalPlays_mixedOptionOnButDeckNotEmpty_hasNoCombination() {
        // Given
        GameSession session = session(config(true, false));
        session.setDeck(new Deck(List.of(card(3))));
        hand(session, 5, 9);
        faceUp(session, 5, 12);
        pile(session, 4);

        // When
        List<List<CardSelection>> plays = session.legalPlays();

        // Then
        assertEquals(2, plays.size());
        assertTrue(plays.stream().allMatch(play -> play.size() == 1 && play.get(0).source() == CardSource.HAND));
    }

    @Test
    void legalPlays_mixedOptionOff_hasNoCombination() {
        // Given
        GameSession session = session();
        hand(session, 5, 9);
        faceUp(session, 5, 12);
        pile(session, 4);

        // When
        List<List<CardSelection>> plays = session.legalPlays();

        // Then
        assertEquals(2, plays.size());
    }

    @Test
    void legalPlays_mixedValueDoesNotFitPile_hasNoCombination() {
        // Given
        GameSession session = session(config(true, false));
        hand(session, 5);
        faceUp(session, 5);
        pile(session, 7);

        // When
        List<List<CardSelection>> plays = session.legalPlays();

        // Then
        assertTrue(plays.isEmpty());
    }

    @Test
    void legalPlays_setupNotComplete_isEmpty() {
        // Given
        GameSession session = session();
        hand(session, 5);
        session.setSetupComplete(false);

        // When
        List<List<CardSelection>> plays = session.legalPlays();

        // Then
        assertTrue(plays.isEmpty());
    }

    @Test
    void legalPlays_gameFinished_isEmpty() {
        // Given
        GameSession session = session();
        hand(session, 5);
        session.setFinished(true);

        // When
        List<List<CardSelection>> plays = session.legalPlays();

        // Then
        assertTrue(plays.isEmpty());
    }

    @Test
    void legalPlays_customRules_followConfiguredRanks() {
        // Given
        GameSession session = session(customConfig());
        hand(session, 2, 3, 5, 6, 11, 13);
        pile(session, 9, 7);

        // When
        Set<Integer> values = playableValues(session);

        // Then
        assertEquals(Set.of(2, 3, 5, 6, 11), values);
    }

    // ---------------------------------------------------------------- property check

    private static Card randomCard(GameConfig config, Random random) {
        return card(config, 2 + random.nextInt(13), Suit.values()[random.nextInt(Suit.values().length)]);
    }

    private static void addRandomCards(java.util.Collection<Card> target, int count, GameConfig config, Random random) {
        for (int i = 0; i < count; i++) {
            target.add(randomCard(config, random));
        }
    }

    private static GameSession randomSession(long seed) {
        Random random = new Random(seed);
        GameConfig[] configs = {DEFAULTS, customConfig(), config(true, false), config(true, true), config(false, true)};
        GameConfig config = configs[random.nextInt(configs.length)];
        GameSession session = GameSession.builder().sessionId("random-" + seed).config(config).build();
        for (int i = 0; i < 3; i++) {
            session.addPlayer("p" + i, "player" + i);
        }
        session.setStarted(true);
        session.setSetupComplete(true);
        session.setCurrentIndex(random.nextInt(3));
        List<Card> deck = new ArrayList<>();
        if (random.nextBoolean()) {
            addRandomCards(deck, 5, config, random);
        }
        session.setDeck(new Deck(deck));
        for (Player player : session.getPlayers()) {
            if (player != session.getCurrentPlayer()) {
                addRandomCards(player.getHand(), 1 + random.nextInt(3), config, random);
                continue;
            }
            int state = random.nextInt(3);
            if (state == 0) {
                addRandomCards(player.getHand(), 1 + random.nextInt(6), config, random);
            }
            if (state <= 1) {
                addRandomCards(player.getFaceUp(), (state == 1 ? 1 : 0) + random.nextInt(3), config, random);
            }
            addRandomCards(player.getFaceDown(), (state == 2 ? 1 : 0) + random.nextInt(3), config, random);
            player.sortHand();
            player.sortFaceUp();
        }
        addRandomCards(session.getDiscardPile(), random.nextInt(5), config, random);
        return session;
    }

    private static CardSource activeZone(Player player) {
        if (!player.getHand().isEmpty()) {
            return CardSource.HAND;
        }
        return player.getFaceUp().isEmpty() ? CardSource.FACE_DOWN : CardSource.FACE_UP;
    }

    private static int zoneSize(Player player, CardSource zone) {
        return switch (zone) {
            case HAND -> player.getHand().size();
            case FACE_UP -> player.getFaceUp().size();
            case FACE_DOWN -> player.getFaceDown().size();
        };
    }

    @Test
    void legalPlays_randomStates_everyEntryIsAcceptedAndNoSinglePlayIsMissing() {
        for (long seed = 1; seed <= 400; seed++) {
            // Given
            GameSession session = randomSession(seed);
            Player player = session.getCurrentPlayer();
            CardSource zone = activeZone(player);

            // When
            List<List<CardSelection>> plays = session.legalPlays();

            // Then
            String context = "seed " + seed;
            assertEquals(plays.size(), new HashSet<>(plays).size(), context);
            for (List<CardSelection> play : plays) {
                PlayResult result = copy(session).playSelections(play);
                assertNotEquals(PlayResult.INVALID, result, context + " play " + play);
                if (zone != CardSource.FACE_DOWN) {
                    assertEquals(PlayResult.SUCCESS, result, context + " play " + play);
                }
            }
            for (int index = 0; index < zoneSize(player, zone); index++) {
                List<CardSelection> single = List.of(sel(zone, index));
                boolean succeeds = copy(session).playSelections(single) == PlayResult.SUCCESS;
                int value = cardAt(player, single.get(0)).getValue();
                boolean valueListed = plays.stream().anyMatch(play -> play.size() == 1 && play.get(0).source() == zone
                        && cardAt(player, play.get(0)).getValue() == value);
                assertTrue(valueListed || !succeeds, context + " missing " + single);
                assertTrue(zone == CardSource.FACE_DOWN || succeeds || !plays.contains(single),
                        context + " wrongly listed " + single);
            }
            if (plays.isEmpty()) {
                assertFalse(session.getDiscardPile().isEmpty(), context);
                assertNotEquals(PlayResult.INVALID, copy(session).pickupPile(), context);
            }
        }
    }

    // ---------------------------------------------------------------- bots in the lobby

    private static GameSession lobby() {
        GameSession session = GameSession.builder().sessionId("lobby").ownerId("owner").build();
        session.addPlayer("owner", "alice");
        return session;
    }

    @Test
    void addBot_twoBots_areNumberedAndMarkedAsBots() {
        // Given
        GameSession session = lobby();

        // When
        Player first = session.addBot(BotType.BEGINNER);
        Player second = session.addBot(BotType.BEGINNER);

        // Then
        assertEquals("Beginner Bot 1", first.getUsername());
        assertEquals("Beginner Bot 2", second.getUsername());
        assertTrue(first.isBot());
        assertTrue(first.getPlayerId().startsWith(GameSession.BOT_ID_PREFIX));
        assertNotEquals(first.getPlayerId(), second.getPlayerId());
        assertEquals(3, session.getPlayers().size());
    }

    @Test
    void addBot_afterRemovingMiddleBot_reusesFreedNumber() {
        // Given
        GameSession session = lobby();
        session.addBot(BotType.BEGINNER);
        Player second = session.addBot(BotType.BEGINNER);
        session.addBot(BotType.BEGINNER);
        session.removeBot(second.getPlayerId());

        // When
        Player added = session.addBot(BotType.BEGINNER);

        // Then
        assertEquals("Beginner Bot 2", added.getUsername());
    }

    @Test
    void addBot_tableFull_isRefused() {
        // Given
        GameSession session = lobby();
        for (int i = 1; i < session.seatCapacity(); i++) {
            session.addBot(BotType.BEGINNER);
        }

        // When / Then
        assertThrows(IllegalStateException.class, () -> session.addBot(BotType.BEGINNER));
        assertEquals(session.seatCapacity(), session.getPlayers().size());
    }

    @Test
    void addBot_gameStarted_isRefused() {
        // Given
        GameSession session = lobby();
        session.setStarted(true);

        // When / Then
        assertThrows(IllegalStateException.class, () -> session.addBot(BotType.BEGINNER));
    }

    @Test
    void removeBot_gameStarted_isRefused() {
        // Given
        GameSession session = lobby();
        Player bot = session.addBot(BotType.BEGINNER);
        session.setStarted(true);

        // When
        boolean removed = session.removeBot(bot.getPlayerId());

        // Then
        assertFalse(removed);
        assertEquals(2, session.getPlayers().size());
    }

    @Test
    void removeBot_humanId_isRefused() {
        // Given
        GameSession session = lobby();

        // When
        boolean removed = session.removeBot("owner");

        // Then
        assertFalse(removed);
        assertEquals(1, session.getPlayers().size());
    }

    @Test
    void removePlayer_ownerLeavesWithAnotherHuman_passesOwnershipToHumanNotBot() {
        // Given
        GameSession session = lobby();
        session.addBot(BotType.BEGINNER);
        session.addPlayer("second", "bob");

        // When
        session.removePlayer("owner");

        // Then
        assertEquals("second", session.getOwnerId());
        assertEquals(2, session.getPlayers().size());
    }

    @Test
    void removePlayer_nonOwnerLeaves_keepsOwner() {
        // Given
        GameSession session = lobby();
        session.addPlayer("second", "bob");
        session.addBot(BotType.BEGINNER);

        // When
        session.removePlayer("second");

        // Then
        assertEquals("owner", session.getOwnerId());
        assertEquals(2, session.getPlayers().size());
    }

    @Test
    void removePlayer_lastHumanLeavesWithOnlyBots_emptiesLobby() {
        // Given
        GameSession session = lobby();
        session.addBot(BotType.BEGINNER);
        session.addBot(BotType.BEGINNER);

        // When
        session.removePlayer("owner");

        // Then
        assertTrue(session.getPlayers().isEmpty());
    }
}
