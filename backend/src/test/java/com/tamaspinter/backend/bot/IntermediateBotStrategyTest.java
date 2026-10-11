package com.tamaspinter.backend.bot;

import com.tamaspinter.backend.game.CardSelection;
import com.tamaspinter.backend.game.CardSource;
import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.model.Deck;
import com.tamaspinter.backend.model.Player;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.tamaspinter.backend.bot.BotTables.card;
import static com.tamaspinter.backend.bot.BotTables.give;
import static com.tamaspinter.backend.bot.BotTables.human;
import static com.tamaspinter.backend.bot.BotTables.pile;
import static com.tamaspinter.backend.bot.BotTables.running;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntermediateBotStrategyTest {

    private static final GameConfig DEFAULTS = GameConfig.defaultGameConfig();
    /** Twelve ordinary cards ending in a 3, so nothing burns by count. */
    private static final int[] BIG_PILE = {4, 5, 7, 11, 12, 13, 14, 4, 5, 7, 11, 3};

    private final IntermediateBotStrategy strategy = new IntermediateBotStrategy();

    /** Never takes the occasional random play. */
    private static final class NeverRandom extends Random {
        private static final long serialVersionUID = 1L;

        @Override
        public int nextInt(int bound) {
            return bound - 1;
        }
    }

    /** Fails when asked for anything: the strategy must not use randomness here. */
    private static final class NoRandom extends Random {
        private static final long serialVersionUID = 1L;

        @Override
        public int nextInt(int bound) {
            throw new AssertionError("randomness used");
        }
    }

    /** Takes the random play when offered, then picks the last of the plays on offer. */
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

    private static Player intermediate(String id) {
        return Player.builder().playerId(id).username(id).botType(BotType.INTERMEDIATE).build();
    }

    /** A running game with an empty draw pile and a card memory; the intermediate bot "b1" moves first. */
    private static GameSession table(GameConfig config, Player... opponents) {
        List<Player> seats = new ArrayList<>();
        seats.add(intermediate("b1"));
        seats.addAll(List.of(opponents));
        GameSession session = running(config, seats.toArray(new Player[0]));
        session.setObserver(new CardMemory());
        return session;
    }

    private static void faceDown(GameSession session, Player player, int count) {
        for (int i = 0; i < count; i++) {
            player.getFaceDown().add(card(session.getConfig(), 9));
        }
    }

    private static void faceUp(GameSession session, Player player, int... values) {
        for (int value : values) {
            player.getFaceUp().add(card(session.getConfig(), value));
        }
        player.sortFaceUp();
    }

    private static Player bot(GameSession session) {
        return session.getPlayers().get(0);
    }

    private static Player seat(GameSession session, int index) {
        return session.getPlayers().get(index);
    }

    private List<CardSelection> choose(GameSession session) {
        List<List<CardSelection>> legalPlays = session.legalPlays();
        List<CardSelection> play = strategy.choosePlay(BotView.of(session, bot(session)), legalPlays, new NeverRandom());
        assertTrue(play == null || legalPlays.contains(play), "choice must be one of the legal plays");
        return play;
    }

    private static List<Integer> valuesOf(GameSession session, List<CardSelection> play) {
        Player player = bot(session);
        return play.stream().map(selection -> switch (selection.source()) {
            case HAND -> new ArrayList<>(player.getHand()).get(selection.index()).getValue();
            case FACE_UP -> new ArrayList<>(player.getFaceUp()).get(selection.index()).getValue();
            case FACE_DOWN -> new ArrayList<>(player.getFaceDown()).get(selection.index()).getValue();
        }).toList();
    }

    private static List<Integer> values(Collection<Card> cards) {
        return cards.stream().map(Card::getValue).toList();
    }

    private static List<Card> cards(int... values) {
        List<Card> cards = new ArrayList<>();
        for (int value : values) {
            cards.add(card(DEFAULTS, value));
        }
        return cards;
    }

    /** A second, empty-handed opponent seat so the opponent under test is not the only one. */
    private static GameSession twoPlayerTable(GameConfig config) {
        GameSession session = table(config, human("h1"));
        give(session, seat(session, 1), 14, 13, 12);
        faceDown(session, seat(session, 1), 3);
        return session;
    }

    private static GameConfig twoDecks() {
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

    /** 3 burns and plays again, 5 is an always-playable joker, 7 is 'smaller', everything else is ordinary. */
    private static GameConfig custom() {
        Map<Integer, CardRule> rules = new HashMap<>();
        rules.put(3, CardRule.BURNER);
        rules.put(5, CardRule.JOKER);
        rules.put(7, CardRule.SMALLER);
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

    // ---------------------------------------------------------------- play choice

    @Test
    void choosePlay_ordinaryAndPowerCardsFit_playsLowOrdinaryCard() {
        // Given
        GameSession session = twoPlayerTable(DEFAULTS);
        give(session, bot(session), 2, 5, 8, 10, 14);
        pile(session, 4);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(5), valuesOf(session, play));
    }

    @Test
    void choosePlay_copiesOfLowValue_playsAllOfThem() {
        // Given
        GameSession session = twoPlayerTable(DEFAULTS);
        give(session, bot(session), 7, 7, 7, 12);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(7, 7, 7), valuesOf(session, play));
    }

    @Test
    void choosePlay_bigPile_burnsIt() {
        // Given
        GameSession session = twoPlayerTable(DEFAULTS);
        give(session, bot(session), 4, 10);
        pile(session, BIG_PILE);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(10), valuesOf(session, play));
    }

    @Test
    void choosePlay_smallPile_keepsBurner() {
        // Given
        GameSession session = twoPlayerTable(DEFAULTS);
        give(session, bot(session), 4, 10);
        pile(session, 3);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(4), valuesOf(session, play));
    }

    @Test
    void choosePlay_emptyPile_keepsBurnerAndJoker() {
        // Given
        GameSession session = twoPlayerTable(DEFAULTS);
        give(session, bot(session), 2, 10, 13);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(13), valuesOf(session, play));
    }

    @Test
    void choosePlay_onlyJokerAndBurnerFitBigPile_escapesWithBurner() {
        // Given
        GameSession session = twoPlayerTable(DEFAULTS);
        give(session, bot(session), 2, 10, 4);
        pile(session, 4, 5, 7, 11, 12, 13, 4, 5, 7, 11, 12, 9);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(10), valuesOf(session, play));
    }

    @Test
    void choosePlay_completesBurnByCountOnBigPile() {
        // Given
        GameSession session = twoPlayerTable(DEFAULTS);
        give(session, bot(session), 7, 13);
        pile(session, 4, 5, 11, 12, 4, 5, 7, 7, 7);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(7), valuesOf(session, play));
    }

    @Test
    void choosePlay_nextPlayerFaceUpCannotBeatHighCard_playsIt() {
        // Given: the next player has only 5 and 7 face up and one face-down card left
        GameSession session = table(DEFAULTS, human("h1"));
        faceUp(session, seat(session, 1), 5, 7);
        faceDown(session, seat(session, 1), 1);
        give(session, bot(session), 4, 12);
        pile(session, 3, 4, 3, 4, 3);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(12), valuesOf(session, play));
    }

    @Test
    void choosePlay_nextPlayerFaceUpBeatsHighCard_playsCheapCard() {
        // Given: the same table, but the next player's face-up king and ace beat the queen anyway
        GameSession session = table(DEFAULTS, human("h1"));
        faceUp(session, seat(session, 1), 13, 14);
        faceDown(session, seat(session, 1), 1);
        give(session, bot(session), 4, 12);
        pile(session, 3, 4, 3, 4, 3);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(4), valuesOf(session, play));
    }

    @Test
    void choosePlay_reverse_pressuresThePlayerItHandsTheTurnTo() {
        // Given: after a reverse the turn goes to h2, who cannot play on a 9; h1 can
        GameSession session = table(DEFAULTS, human("h1"), human("h2"));
        faceUp(session, seat(session, 1), 13, 14);
        faceUp(session, seat(session, 2), 4, 5);
        faceDown(session, seat(session, 1), 1);
        faceDown(session, seat(session, 2), 1);
        give(session, bot(session), 7, 9);
        pile(session, 3, 4, 3, 4, 3);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(9), valuesOf(session, play));
    }

    @Test
    void choosePlay_opponentPickedUpLowCards_playsCardTheyCannotBeat() {
        // Given: everyone saw h1 pick up 4, 11, 11, and that is their whole hand
        GameSession session = table(DEFAULTS, human("h1"));
        Player opponent = seat(session, 1);
        faceDown(session, opponent, 3);
        session.setCurrentIndex(1);
        pile(session, 4, 11, 11);
        session.pickupPile();
        give(session, bot(session), 7, 12);
        pile(session, 3, 4, 3, 4, 5);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(4, 11, 11), BotView.of(session, bot(session)).knownHand("h1"));
        assertEquals(List.of(12), valuesOf(session, play));
    }

    @Test
    void choosePlay_opponentPickedUpHighCards_playsCheapCard() {
        // Given: everyone saw h1 pick up 13, 13, 14: they beat the queen as well, so blocking is pointless
        GameSession session = table(DEFAULTS, human("h1"));
        Player opponent = seat(session, 1);
        faceDown(session, opponent, 3);
        session.setCurrentIndex(1);
        pile(session, 13, 13, 14);
        session.pickupPile();
        give(session, bot(session), 7, 12);
        pile(session, 3, 4, 3, 4, 5);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(7), valuesOf(session, play));
    }

    @Test
    void choosePlay_customRules_keepsCustomBurnerAndJoker() {
        // Given
        GameSession session = twoPlayerTable(custom());
        give(session, bot(session), 3, 5, 9);
        pile(session, 4);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(9), valuesOf(session, play));
    }

    @Test
    void choosePlay_customBurnerOnBigPile_burnsIt() {
        // Given
        GameSession session = twoPlayerTable(custom());
        give(session, bot(session), 3, 9);
        pile(session, 4, 6, 8, 11, 12, 13, 14, 4, 6, 8, 11, 2);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(3), valuesOf(session, play));
    }

    @Test
    void choosePlay_twoDecks_completesBurnOfSix() {
        // Given: five 7s on top; with two decks six burn
        GameSession session = twoPlayerTable(twoDecks());
        give(session, bot(session), 7, 13);
        pile(session, 4, 5, 11, 12, 7, 7, 7, 7, 7);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(7), valuesOf(session, play));
    }

    @Test
    void choosePlay_twoDecksFourOfAKind_doesNotCountItAsBurn() {
        // Given: three 7s on top and one in hand do not burn with two decks; the cheaper 5 cannot be played
        GameSession session = twoPlayerTable(twoDecks());
        give(session, bot(session), 7, 10);
        pile(session, 5, 7, 7, 7);

        // When
        List<CardSelection> play = choose(session);

        // Then: the burner is kept for a bigger pile
        assertEquals(List.of(7), valuesOf(session, play));
    }

    @Test
    void choosePlay_handEmpty_keepsStrongFaceUpCards() {
        // Given
        GameSession session = twoPlayerTable(DEFAULTS);
        faceUp(session, bot(session), 4, 10, 14);
        pile(session, 3);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(new CardSelection(CardSource.FACE_UP, 0)), play);
    }

    @Test
    void choosePlay_onlyFaceDownLeft_flipsFirstCard() {
        // Given
        GameSession session = twoPlayerTable(DEFAULTS);
        bot(session).getFaceDown().addAll(cards(3, 9, 14));
        pile(session, 12);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertEquals(List.of(new CardSelection(CardSource.FACE_DOWN, 0)), play);
    }

    @Test
    void choosePlay_nothingFits_returnsNullToPickUp() {
        // Given
        GameSession session = twoPlayerTable(DEFAULTS);
        give(session, bot(session), 3, 4);
        pile(session, 13);

        // When
        List<CardSelection> play = choose(session);

        // Then
        assertTrue(session.legalPlays().isEmpty());
        assertNull(play);
    }

    @Test
    void choosePlay_playsDrawCards_usesNoRandomness() {
        // Given: the draw pile still refills the hand
        GameSession session = twoPlayerTable(DEFAULTS);
        session.setDeck(new Deck(cards(5, 6, 7)));
        give(session, bot(session), 4, 9, 13);
        pile(session, 3);

        // When
        List<CardSelection> play = strategy.choosePlay(BotView.of(session, bot(session)), session.legalPlays(),
                new NoRandom());

        // Then
        assertEquals(List.of(4), valuesOf(session, play));
    }

    @Test
    void choosePlay_randomEscape_picksAnotherGoodLegalPlay() {
        // Given: the draw pile is empty, so the escape may trigger
        GameSession session = twoPlayerTable(DEFAULTS);
        give(session, bot(session), 4, 5, 7, 13);
        pile(session, 3);
        List<List<CardSelection>> legalPlays = session.legalPlays();

        // When
        List<CardSelection> play = strategy.choosePlay(BotView.of(session, bot(session)), legalPlays, new AlwaysRandom());
        List<CardSelection> best = choose(session);

        // Then
        assertTrue(legalPlays.contains(play));
        assertFalse(play.equals(best));
        assertEquals(List.of(4), valuesOf(session, best));
    }

    @Test
    void choosePlay_manyRandomTables_alwaysChoosesALegalPlayWhenThereIsOne() {
        // Given
        Random dealer = new Random(42);
        Random random = new Random(7);
        List<GameConfig> configs = List.of(DEFAULTS, custom(), twoDecks());
        int checked = 0;

        // When / Then
        for (int round = 0; round < 300; round++) {
            GameSession session = table(configs.get(round % configs.size()), human("h1"), human("h2"));
            int handSize = 1 + dealer.nextInt(6);
            int pileSize = dealer.nextInt(8);
            for (int i = 0; i < handSize; i++) {
                give(session, bot(session), 2 + dealer.nextInt(13));
            }
            for (int i = 0; i < pileSize; i++) {
                pile(session, 2 + dealer.nextInt(13));
            }
            give(session, seat(session, 1), 2 + dealer.nextInt(13));
            faceUp(session, seat(session, 2), 2 + dealer.nextInt(13));
            List<List<CardSelection>> legalPlays = session.legalPlays();
            List<CardSelection> play = strategy.choosePlay(BotView.of(session, bot(session)), legalPlays, random);
            if (legalPlays.isEmpty()) {
                assertNull(play);
            } else {
                assertNotNull(play);
                assertTrue(legalPlays.contains(play));
                checked++;
            }
        }
        assertTrue(checked > 100);
    }

    // ---------------------------------------------------------------- setup swap

    @Test
    void chooseSetupSwap_powerCardsInHand_goFaceUp() {
        // Given
        List<Card> hand = cards(10, 3, 4);
        List<Card> faceUp = cards(5, 14, 2);

        // When
        SetupSwap swap = strategy.chooseSetupSwap(hand, faceUp, DEFAULTS);

        // Then: 10, 2 and the ace face up; the 5 comes into the hand
        assertEquals(List.of(0), swap.handIndices());
        assertEquals(List.of(0), swap.faceUpIndices());
    }

    @Test
    void chooseSetupSwap_pairOfQueens_preferredOverKingAndQueen() {
        // Given
        List<Card> hand = cards(12, 12, 3);
        List<Card> faceUp = cards(14, 13, 4);

        // When
        SetupSwap swap = strategy.chooseSetupSwap(hand, faceUp, DEFAULTS);

        // Then: ace and both queens face up
        assertEquals(List.of(0, 1), swap.handIndices());
        assertEquals(List.of(1, 2), swap.faceUpIndices());
    }

    @Test
    void chooseSetupSwap_appliedThroughSession_leavesStrongestCardsFaceUp() {
        // Given
        GameSession session = twoPlayerTable(DEFAULTS);
        session.setStarted(true);
        session.setSetupComplete(false);
        bot(session).setReady(false);
        bot(session).getHand().addAll(cards(3, 8, 14));
        bot(session).getFaceUp().addAll(cards(4, 5, 9));
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
        // When
        SetupSwap swap = strategy.chooseSetupSwap(cards(3, 4, 5), cards(10, 13, 14), DEFAULTS);

        // Then
        assertTrue(swap.isEmpty());
    }

    @Test
    void chooseSetupSwap_emptyHandOrFaceUp_swapsNothing() {
        // When
        SetupSwap noHand = strategy.chooseSetupSwap(List.of(), cards(3, 14), DEFAULTS);
        SetupSwap noFaceUp = strategy.chooseSetupSwap(cards(3, 14), List.of(), DEFAULTS);

        // Then
        assertTrue(noHand.isEmpty());
        assertTrue(noFaceUp.isEmpty());
    }

    @Test
    void chooseSetupSwap_manyCards_greedyLayoutIsValid() {
        // Given: 20 cards are too many to try every split
        List<Card> hand = cards(3, 4, 5, 7, 7, 10, 2, 14, 13, 12);
        List<Card> faceUp = cards(3, 4, 5, 7, 9, 11, 11, 11, 12, 13);

        // When
        SetupSwap swap = strategy.chooseSetupSwap(hand, faceUp, DEFAULTS);

        // Then: the five strong hand cards trade places with the five weakest face-up cards
        assertEquals(List.of(5, 6, 7, 8, 9), swap.handIndices());
        assertEquals(List.of(0, 1, 2, 3, 4), swap.faceUpIndices());
    }

    @Test
    void chooseSetupSwap_customRules_putsCustomPowerCardsFaceUp() {
        // Given: under the custom rules 3 burns and 5 is a joker, while 10 and 2 are ordinary
        GameConfig config = custom();
        List<Card> hand = List.of(card(config, 3), card(config, 5), card(config, 4));
        List<Card> faceUp = List.of(card(config, 2), card(config, 10), card(config, 14));

        // When
        SetupSwap swap = strategy.chooseSetupSwap(hand, faceUp, config);

        // Then
        assertEquals(List.of(0, 1), swap.handIndices());
        assertEquals(List.of(0, 1), swap.faceUpIndices());
    }
}
