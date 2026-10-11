package com.tamaspinter.backend.bot;

import com.tamaspinter.backend.game.CardSelection;
import com.tamaspinter.backend.game.CardSource;
import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.game.PlayResult;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.model.Deck;
import com.tamaspinter.backend.model.Player;
import com.tamaspinter.backend.model.Suit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeginnerBotStrategyTest {

    private static final GameConfig DEFAULTS = GameConfig.defaultGameConfig();

    private final BeginnerBotStrategy strategy = new BeginnerBotStrategy();

    /** Never takes the occasional random play, so the preference order decides. */
    private static final class NeverRandom extends Random {
        private static final long serialVersionUID = 1L;

        @Override
        public int nextInt(int bound) {
            return bound - 1;
        }
    }

    /** Always takes the occasional random play when it is on offer, and then picks the last legal play. */
    private static final class AlwaysRandom extends Random {
        private static final long serialVersionUID = 1L;
        private int calls;

        @Override
        public int nextInt(int bound) {
            calls++;
            return calls == 1 ? 0 : bound - 1;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Card card(GameConfig config, int value, Suit suit) {
        return Card.builder()
                .suit(suit)
                .value(value)
                .rule(config.getCardRule(value))
                .alwaysPlayable(config.isAlwaysPlayable(value))
                .build();
    }

    private static List<Card> cards(GameConfig config, int... values) {
        List<Card> cards = new ArrayList<>();
        for (int value : values) {
            cards.add(card(config, value, Suit.HEARTS));
        }
        return cards;
    }

    /** A running two-player game where the bot "b1" is to move and the draw pile is empty. */
    private static GameSession table(GameConfig config) {
        GameSession session = GameSession.builder().sessionId("strategy").config(config).build();
        session.addPlayer("b1", "bot");
        session.addPlayer("h1", "human");
        session.setStarted(true);
        session.setSetupComplete(true);
        session.setDeck(new Deck(List.of()));
        session.getPlayers().get(1).getHand().add(card(config, 14, Suit.CLUBS));
        return session;
    }

    private static Player bot(GameSession session) {
        return session.getPlayers().get(0);
    }

    private static void hand(GameSession session, int... values) {
        bot(session).getHand().addAll(cards(session.getConfig(), values));
        bot(session).sortHand();
    }

    private static void pile(GameSession session, int... values) {
        session.getDiscardPile().addAll(cards(session.getConfig(), values));
    }

    private List<CardSelection> choose(GameSession session) {
        return strategy.choosePlay(BotView.of(session, bot(session)), session.legalPlays(), new NeverRandom());
    }

    private static List<Integer> valuesOf(GameSession session, List<CardSelection> play) {
        Player player = bot(session);
        return play.stream().map(selection -> switch (selection.source()) {
            case HAND -> new ArrayList<>(player.getHand()).get(selection.index()).getValue();
            case FACE_UP -> new ArrayList<>(player.getFaceUp()).get(selection.index()).getValue();
            case FACE_DOWN -> new ArrayList<>(player.getFaceDown()).get(selection.index()).getValue();
        }).toList();
    }

    private static List<Integer> values(java.util.Collection<Card> cards) {
        return cards.stream().map(Card::getValue).toList();
    }

    /** Every rank plays by the plain higher-or-equal rule; nothing is special. */
    private static GameConfig allDefaultConfig() {
        return GameConfig.builder().faceDownCount(3).faceUpCount(3).handCount(3).burnCount(4).build();
    }

    /** 3 burns and plays again, 5 is an always-playable joker, everything else is ordinary. */
    private static GameConfig burnerThreeJokerFiveConfig() {
        Map<Integer, CardRule> rules = new HashMap<>();
        rules.put(3, CardRule.BURNER);
        rules.put(5, CardRule.JOKER);
        Map<Integer, Boolean> always = new HashMap<>();
        always.put(5, true);
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

    private static GameConfig twoDeckConfig() {
        return GameConfig.builder()
                .decksCount(2)
                .faceDownCount(3)
                .faceUpCount(3)
                .handCount(3)
                .burnCount(6)
                .cardRuleMap(new HashMap<>(DEFAULTS.getCardRuleMap()))
                .alwaysPlayableMap(new HashMap<>(DEFAULTS.getAlwaysPlayableMap()))
                .canPlayAgainMap(new HashMap<>(DEFAULTS.getCanPlayAgainMap()))
                .build();
    }

    // ---------------------------------------------------------------- setup swap

    @Test
    void chooseSetupSwap_highCardsInHand_movesThemFaceUp() {
        // Given
        List<Card> hand = cards(DEFAULTS, 3, 13, 14);
        List<Card> faceUp = cards(DEFAULTS, 4, 5, 12);

        // When
        SetupSwap swap = strategy.chooseSetupSwap(hand, faceUp, DEFAULTS);

        // Then
        assertEquals(List.of(1, 2), swap.handIndices());
        assertEquals(List.of(0, 1), swap.faceUpIndices());
    }

    @Test
    void chooseSetupSwap_specialCards_goFaceUpBeforeAces() {
        // Given
        List<Card> hand = cards(DEFAULTS, 2, 5, 10);
        List<Card> faceUp = cards(DEFAULTS, 4, 13, 14);

        // When
        SetupSwap swap = strategy.chooseSetupSwap(hand, faceUp, DEFAULTS);

        // Then: 10 (burner) and 2 (joker) beat the ace; the king is the weakest of the strong three
        assertEquals(List.of(0, 2), swap.handIndices());
        assertEquals(List.of(0, 1), swap.faceUpIndices());
    }

    @Test
    void chooseSetupSwap_appliedThroughSession_leavesStrongestCardsFaceUp() {
        // Given
        GameSession session = table(DEFAULTS);
        session.setSetupComplete(false);
        bot(session).setReady(false);
        bot(session).getHand().addAll(cards(DEFAULTS, 3, 8, 14));
        bot(session).getFaceUp().addAll(cards(DEFAULTS, 4, 5, 9));
        SetupSwap swap = strategy.chooseSetupSwap(List.copyOf(bot(session).getHand()),
                List.copyOf(bot(session).getFaceUp()), DEFAULTS);

        // When
        boolean swapped = session.swapStartingCards("b1", swap.handIndices(), swap.faceUpIndices());

        // Then
        assertTrue(swapped);
        assertEquals(List.of(8, 9, 14), values(bot(session).getFaceUp()));
        assertEquals(List.of(3, 4, 5), values(bot(session).getHand()));
    }

    @Test
    void chooseSetupSwap_alreadyIdeal_swapsNothing() {
        // Given
        List<Card> hand = cards(DEFAULTS, 3, 4, 5);
        List<Card> faceUp = cards(DEFAULTS, 10, 13, 14);

        // When
        SetupSwap swap = strategy.chooseSetupSwap(hand, faceUp, DEFAULTS);

        // Then
        assertTrue(swap.isEmpty());
    }

    @Test
    void chooseSetupSwap_equalCards_keepsDealtPositions() {
        // Given
        List<Card> hand = cards(DEFAULTS, 11, 3, 4);
        List<Card> faceUp = cards(DEFAULTS, 11, 12, 13);

        // When
        SetupSwap swap = strategy.chooseSetupSwap(hand, faceUp, DEFAULTS);

        // Then
        assertTrue(swap.isEmpty());
    }

    @Test
    void chooseSetupSwap_emptyHandOrFaceUp_swapsNothing() {
        // Given
        List<Card> some = cards(DEFAULTS, 3, 14);

        // When
        SetupSwap noHand = strategy.chooseSetupSwap(List.of(), some, DEFAULTS);
        SetupSwap noFaceUp = strategy.chooseSetupSwap(some, List.of(), DEFAULTS);

        // Then
        assertTrue(noHand.isEmpty());
        assertTrue(noFaceUp.isEmpty());
    }

    // ---------------------------------------------------------------- play choice

    @Test
    void choosePlay_severalOrdinaryValuesFit_playsLowest() {
        // Given
        GameSession session = table(DEFAULTS);
        hand(session, 4, 7, 9, 13);
        pile(session, 5);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(7), valuesOf(session, play));
    }

    @Test
    void choosePlay_copiesOfOrdinaryValue_playsAllOfThem() {
        // Given
        GameSession session = table(DEFAULTS);
        hand(session, 7, 7, 7, 12);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(7, 7, 7), valuesOf(session, play));
    }

    @Test
    void choosePlay_ordinaryAndSpecialFit_keepsSpecials() {
        // Given
        GameSession session = table(DEFAULTS);
        hand(session, 2, 5, 8, 10, 14);
        pile(session, 4);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(5), valuesOf(session, play));
    }

    @Test
    void choosePlay_onlySpecialsFit_playsLeastValuableSpecialAlone() {
        // Given
        GameSession session = table(DEFAULTS);
        hand(session, 2, 2, 8, 8, 3);
        pile(session, 13);

        // When
        List<CardSelection> play = choose(session);

        // Then: the transparent 8 is worth less than the joker 2, and only one is spent
        assertEquals(List.of(8), valuesOf(session, play));
    }

    @Test
    void choosePlay_onlyJokersFit_playsSingleJoker() {
        // Given
        GameSession session = table(DEFAULTS);
        hand(session, 2, 2, 2, 4);
        pile(session, 13);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(2), valuesOf(session, play));
    }

    @Test
    void choosePlay_onlyFaceDownLeft_flipsFirstCard() {
        // Given
        GameSession session = table(DEFAULTS);
        bot(session).getFaceDown().addAll(cards(DEFAULTS, 3, 9, 14));
        pile(session, 12);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(new CardSelection(CardSource.FACE_DOWN, 0)), play);
    }

    @Test
    void choosePlay_handEmpty_playsFromFaceUp() {
        // Given
        GameSession session = table(DEFAULTS);
        bot(session).getFaceUp().addAll(cards(DEFAULTS, 4, 11, 11));
        pile(session, 9);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(new CardSelection(CardSource.FACE_UP, 1), new CardSelection(CardSource.FACE_UP, 2)), play);
    }

    @Test
    void choosePlay_nothingFits_returnsNullToPickUp() {
        // Given
        GameSession session = table(DEFAULTS);
        hand(session, 3, 4);
        pile(session, 13);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertTrue(session.legalPlays().isEmpty());
        assertNull(play);
    }

    @Test
    void choosePlay_onlyMixedPlaysOffered_returnsOneOfThem() {
        // Given
        GameSession session = table(DEFAULTS);
        hand(session, 5);
        List<List<CardSelection>> offered = List.of(
                List.of(new CardSelection(CardSource.HAND, 0), new CardSelection(CardSource.FACE_UP, 0)));

        // When
        List<CardSelection> play = strategy.choosePlay(BotView.of(session, bot(session)), offered, new NeverRandom());

        // Then
        assertEquals(offered.get(0), play);
    }

    @Test
    void choosePlay_allRanksDefault_playsLowestFittingValue() {
        // Given
        GameSession session = table(allDefaultConfig());
        hand(session, 2, 5, 10, 14);
        pile(session, 4);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(5), valuesOf(session, play));
    }

    @Test
    void choosePlay_customBurnerAndJoker_keepsThemWhileOrdinaryFits() {
        // Given
        GameSession session = table(burnerThreeJokerFiveConfig());
        hand(session, 3, 4, 5);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(4), valuesOf(session, play));
    }

    @Test
    void choosePlay_customJokerIsOnlyFit_playsIt() {
        // Given
        GameSession session = table(burnerThreeJokerFiveConfig());
        hand(session, 3, 5, 7);
        pile(session, 9);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(5), valuesOf(session, play));
    }

    @Test
    void choosePlay_customJokerAndBurnerFit_spendsJokerBeforeBurner() {
        // Given
        GameSession session = table(burnerThreeJokerFiveConfig());
        hand(session, 3, 5);
        pile(session, 2);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(5), valuesOf(session, play));
    }

    @Test
    void choosePlay_twoDecksWithIdenticalCards_playsAllCopiesThroughSession() {
        // Given
        GameSession session = table(twoDeckConfig());
        bot(session).getHand().add(card(session.getConfig(), 7, Suit.HEARTS));
        bot(session).getHand().add(card(session.getConfig(), 7, Suit.HEARTS));
        bot(session).getHand().add(card(session.getConfig(), 7, Suit.SPADES));
        bot(session).getHand().add(card(session.getConfig(), 12, Suit.SPADES));
        bot(session).sortHand();

        // When
        List<CardSelection> play = choose(session);
        List<Integer> chosen = valuesOf(session, play);
        PlayResult result = session.playSelections(play);

        // Then
        assertEquals(List.of(7, 7, 7), chosen);
        assertEquals(PlayResult.SUCCESS, result);
        assertEquals(List.of(12), values(bot(session).getHand()));
        assertEquals(3, session.getDiscardPile().size());
    }

    @Test
    void choosePlay_drawPileEmptyAndRandomBranchTaken_playsARandomLegalPlay() {
        // Given
        GameSession session = table(DEFAULTS);
        hand(session, 4, 7, 13);
        List<List<CardSelection>> legalPlays = session.legalPlays();

        // When
        List<CardSelection> play = strategy.choosePlay(BotView.of(session, bot(session)), legalPlays, new AlwaysRandom());

        // Then
        assertEquals(legalPlays.get(legalPlays.size() - 1), play);
        assertEquals(List.of(13), valuesOf(session, play));
    }

    @Test
    void choosePlay_drawPileHasCardsAndHandNotOverfull_ignoresRandomness() {
        // Given
        GameSession session = table(DEFAULTS);
        session.setDeck(new Deck(cards(DEFAULTS, 14, 14)));
        hand(session, 4, 7, 13);

        // When
        List<CardSelection> play = strategy.choosePlay(BotView.of(session, bot(session)), session.legalPlays(),
                new AlwaysRandom());

        // Then
        assertEquals(List.of(4), valuesOf(session, play));
    }

    @Test
    void choosePlay_handLargerThanRefillWithDeckLeft_mayPlayRandomly() {
        // Given
        GameSession session = table(DEFAULTS);
        session.setDeck(new Deck(cards(DEFAULTS, 14, 14)));
        hand(session, 4, 5, 7, 13);
        List<List<CardSelection>> legalPlays = session.legalPlays();

        // When
        List<CardSelection> play = strategy.choosePlay(BotView.of(session, bot(session)), legalPlays, new AlwaysRandom());

        // Then
        assertEquals(List.of(13), valuesOf(session, play));
    }

    @Test
    void choosePlay_randomStates_alwaysReturnsALegalPlayOrNullOnlyWhenNoneExists() {
        GameConfig[] configs = {DEFAULTS, allDefaultConfig(), burnerThreeJokerFiveConfig(), twoDeckConfig()};
        for (long seed = 1; seed <= 300; seed++) {
            // Given
            Random random = new Random(seed);
            GameConfig config = configs[random.nextInt(configs.length)];
            GameSession session = table(config);
            int zone = random.nextInt(3);
            java.util.Collection<Card> target = switch (zone) {
                case 0 -> bot(session).getHand();
                case 1 -> bot(session).getFaceUp();
                default -> bot(session).getFaceDown();
            };
            int count = 1 + random.nextInt(6);
            for (int i = 0; i < count; i++) {
                target.add(card(config, 2 + random.nextInt(13), Suit.values()[random.nextInt(4)]));
            }
            bot(session).sortHand();
            bot(session).sortFaceUp();
            int pileSize = random.nextInt(4);
            for (int i = 0; i < pileSize; i++) {
                session.getDiscardPile().add(card(config, 2 + random.nextInt(13), Suit.DIAMONDS));
            }
            List<List<CardSelection>> legalPlays = session.legalPlays();

            // When
            List<CardSelection> play = strategy.choosePlay(BotView.of(session, bot(session)), legalPlays, random);

            // Then
            String context = "seed " + seed;
            if (legalPlays.isEmpty()) {
                assertNull(play, context);
            } else {
                assertTrue(legalPlays.contains(play), context + " chose " + play);
            }
        }
    }
}
