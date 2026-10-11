package com.tamaspinter.backend.bot;

import com.tamaspinter.backend.game.CardSelection;
import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.game.PlayResult;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.Deck;
import com.tamaspinter.backend.model.Player;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

import static com.tamaspinter.backend.bot.BotTables.card;
import static com.tamaspinter.backend.bot.BotTables.give;
import static com.tamaspinter.backend.bot.BotTables.human;
import static com.tamaspinter.backend.bot.BotTables.pile;
import static com.tamaspinter.backend.bot.BotTables.running;
import static com.tamaspinter.backend.bot.BotTables.startDeterministic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotViewMemoryTest {

    private static final GameConfig DEFAULTS = GameConfig.defaultGameConfig();
    private static final int MOVES = 400;

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

    private static Player intermediate(String id) {
        return Player.builder().playerId(id).username(id).botType(BotType.INTERMEDIATE).build();
    }

    private static Player beginner(String id) {
        return Player.builder().playerId(id).username(id).botType(BotType.BEGINNER).build();
    }

    /** A dealt game with an intermediate bot, a beginner bot and a human, observed by a card memory. */
    private static GameSession dealt(GameConfig config, long seed) {
        GameSession session = GameSession.builder().sessionId("view").ownerId("human-0").config(config).build();
        session.addBot(BotType.INTERMEDIATE);
        session.addBot(BotType.BEGINNER);
        session.addPlayer("human-0", "Human");
        session.setObserver(new CardMemory());
        startDeterministic(session, seed);
        BotTurnRunner.completeSetup(session);
        session.swapStartingCards("human-0", List.of(0, 1), List.of(1, 2));
        session.markReady("human-0");
        return session;
    }

    /** Any seat: a random legal play, or a pickup when nothing fits. */
    private static void randomMove(GameSession session, Random random) {
        List<List<CardSelection>> legalPlays = session.legalPlays();
        PlayResult result = legalPlays.isEmpty()
                ? session.pickupPile()
                : session.playSelections(legalPlays.get(random.nextInt(legalPlays.size())));
        assertNotEquals(PlayResult.INVALID, result);
    }

    private static int count(List<Integer> values, int value) {
        return (int) values.stream().filter(known -> known == value).count();
    }

    /** Checks what the counting bot sees against the real (hidden) table. */
    private static void assertConsistent(GameSession session, Player self) {
        BotView view = BotView.of(session, self);
        int hidden = session.getDeck().getCards().size();
        for (Player player : session.getPlayers()) {
            hidden += player.getFaceDown().size();
            if (player.getPlayerId().equals(self.getPlayerId())) {
                continue;
            }
            List<Integer> known = view.knownHand(player.getPlayerId());
            List<Integer> hand = player.getHand().stream().map(Card::getValue).toList();
            hidden += hand.size() - known.size();
            known.forEach(value -> assertTrue(count(hand, value) >= count(known, value),
                    "known value " + value + " is really in the hand of " + player.getPlayerId()));
        }
        int unseen = IntStream.rangeClosed(CardMemory.MIN_VALUE, CardMemory.MAX_VALUE).map(view::unseen).sum();
        assertEquals(hidden, unseen, "unseen cards = deck + face-down + opponents' unknown hand cards");
    }

    private static void playAndCheck(GameConfig config, long seed) {
        GameSession session = dealt(config, seed);
        Player counter = session.getPlayers().get(0);
        Random random = new Random(seed);
        assertConsistent(session, counter);
        for (int move = 0; move < MOVES && !session.isFinished(); move++) {
            randomMove(session, random);
            assertConsistent(session, counter);
        }
    }

    @Test
    void countingBotView_throughRealGames_matchesPublicInformation() {
        // When / Then
        playAndCheck(DEFAULTS, 1);
        playAndCheck(DEFAULTS, 2);
        playAndCheck(twoDecks(), 3);
    }

    @Test
    void countingBotView_afterPickup_knowsThePickedUpValues() {
        // Given
        GameSession session = running(DEFAULTS, intermediate("b1"), human("h1"));
        session.setObserver(new CardMemory());
        give(session, session.getPlayers().get(1), 3);
        pile(session, 12, 13);
        session.setCurrentIndex(1);

        // When
        session.pickupPile();
        BotView view = BotView.of(session, session.getPlayers().get(0));

        // Then
        assertEquals(List.of(12, 13), view.knownHand("h1"));
        assertEquals(3, view.unseen(12));
        assertEquals(3, view.unseen(13));
        assertEquals(4, view.unseen(3), "the 3 h1 held before was never seen");
    }

    @Test
    void beginnerView_getsNoMemory() {
        // Given
        GameSession session = running(DEFAULTS, beginner("b1"), human("h1"));
        session.setObserver(new CardMemory());
        give(session, session.getPlayers().get(1), 3);
        pile(session, 12, 13);
        session.setCurrentIndex(1);
        session.pickupPile();

        // When
        BotView view = BotView.of(session, session.getPlayers().get(0));

        // Then
        assertEquals(Map.of(), view.knownOpponentHands());
        assertEquals(List.of(), view.knownHand("h1"));
        assertEquals(List.of(), view.unseenByValue());
        assertEquals(0, view.unseen(12));
    }

    @Test
    void countingBotView_withoutMemoryObserver_getsNoMemory() {
        // Given
        GameSession session = running(DEFAULTS, intermediate("b1"), human("h1"));

        // When
        BotView view = BotView.of(session, session.getPlayers().get(0));

        // Then
        assertEquals(Map.of(), view.knownOpponentHands());
        assertEquals(List.of(), view.unseenByValue());
    }

    /** The same table, with the ace either in the opponent's hand (unseen by anyone) or in the draw pile. */
    private static BotView viewWithAce(boolean aceInOpponentHand) {
        GameSession session = running(DEFAULTS, intermediate("b1"), human("h1"));
        session.setObserver(new CardMemory());
        Player bot = session.getPlayers().get(0);
        Player opponent = session.getPlayers().get(1);
        give(session, bot, 4, 7);
        pile(session, 9);
        List<Card> deck = new ArrayList<>();
        deck.add(card(DEFAULTS, 3));
        if (aceInOpponentHand) {
            give(session, opponent, 14);
            deck.add(card(DEFAULTS, 5));
        } else {
            give(session, opponent, 5);
            deck.add(card(DEFAULTS, 14));
        }
        session.setDeck(new Deck(deck));
        return BotView.of(session, bot);
    }

    @Test
    void countingBotView_neverRevealsAnOpponentsUnseenHandCard() {
        // When
        BotView aceInHand = viewWithAce(true);
        BotView aceInDeck = viewWithAce(false);

        // Then: the counts cannot tell where the ace is
        assertEquals(aceInDeck.unseenByValue(), aceInHand.unseenByValue());
        assertEquals(4, aceInHand.unseen(14));
        assertEquals(4, aceInHand.unseen(5));
        assertEquals(List.of(), aceInHand.knownHand("h1"));
        assertEquals(aceInDeck.opponents().get(0).handCount(), aceInHand.opponents().get(0).handCount());
    }
}
