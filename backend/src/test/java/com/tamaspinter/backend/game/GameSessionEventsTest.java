package com.tamaspinter.backend.game;

import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.model.Deck;
import com.tamaspinter.backend.model.Player;
import com.tamaspinter.backend.model.Suit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameSessionEventsTest {

    private GameSession session;

    @BeforeEach
    void setUp() {
        session = GameSession.builder().sessionId("events-session").build();
        session.addPlayer("p1", "alice");
        session.addPlayer("p2", "bob");
    }

    private Card card(int value) {
        GameConfig config = GameConfig.defaultGameConfig();
        return Card.builder()
                .suit(Suit.HEARTS)
                .value(value)
                .rule(config.getCardRule(value))
                .alwaysPlayable(config.isAlwaysPlayable(value))
                .build();
    }

    private Card cardWithRule(int value, CardRule rule) {
        return Card.builder().suit(Suit.SPADES).value(value).rule(rule).alwaysPlayable(false).build();
    }

    private void prepareStartedGame() {
        session.setStarted(true);
        session.setDeck(new Deck(List.of()));
    }

    private List<GameEventType> types() {
        return session.getEvents().stream().map(GameEvent::type).toList();
    }

    private GameEvent onlyEvent() {
        assertEquals(1, session.getEvents().size());
        return session.getEvents().get(0);
    }

    private GameConfig configWithFailedFaceUpPlay() {
        GameConfig defaults = GameConfig.defaultGameConfig();
        return GameConfig.builder()
                .faceDownCount(3)
                .faceUpCount(3)
                .handCount(3)
                .burnCount(4)
                .allowFailedFaceUpPlay(true)
                .cardRuleMap(defaults.getCardRuleMap())
                .alwaysPlayableMap(defaults.getAlwaysPlayableMap())
                .canPlayAgainMap(defaults.getCanPlayAgainMap())
                .build();
    }

    // =========================================================================
    // Play and pickup events
    // =========================================================================

    @Test
    void playFromHand_recordsPlayedEventWithCards() {
        // Given
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        Card seven = card(7);
        p1.getHand().add(seven);
        p1.getHand().add(card(4)); // keeps p1 in the game

        // When
        session.playCards(List.of(seven));

        // Then
        GameEvent event = onlyEvent();
        assertEquals(GameEventType.PLAYED, event.type());
        assertEquals("p1", event.playerId());
        assertEquals("alice", event.username());
        assertEquals(1, event.count());
        assertEquals(1, event.cards().size());
        assertEquals(7, event.cards().get(0).getValue());
        assertEquals(1L, event.seq());
    }

    @Test
    void pickupPile_recordsPickedUpEventWithPileSize() {
        // Given
        prepareStartedGame();
        session.getDiscardPile().add(card(5));
        session.getDiscardPile().add(card(6));

        // When
        session.pickupPile();

        // Then
        GameEvent event = onlyEvent();
        assertEquals(GameEventType.PICKED_UP, event.type());
        assertEquals("alice", event.username());
        assertEquals(2, event.count());
        assertTrue(event.cards().isEmpty());
    }

    @Test
    void failedBlindFlip_recordsFailedFlipWithRevealedCardAndPile() {
        // Given
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        Card blindCard = card(3);
        p1.getFaceDown().add(blindCard);
        p1.getFaceDown().add(card(5)); // keeps p1 in the game
        session.getDiscardPile().add(card(9));

        // When
        PlayResult result = session.playCards(List.of(blindCard));

        // Then
        assertEquals(PlayResult.PICKUP, result);
        GameEvent event = onlyEvent();
        assertEquals(GameEventType.FAILED_FLIP, event.type());
        assertEquals(3, event.cards().get(0).getValue());
        assertEquals(2, event.count()); // pile card plus the revealed card
    }

    @Test
    void failedFaceUpPlay_recordsFailedPlayWithSelectedCards() {
        // Given
        prepareStartedGame();
        session.setConfig(configWithFailedFaceUpPlay());
        Player p1 = session.getPlayers().get(0);
        Card illegal = card(3);
        p1.getFaceUp().add(illegal);
        p1.getFaceDown().add(card(5));
        session.getDiscardPile().add(card(9));

        // When
        PlayResult result = session.playCards(List.of(illegal));

        // Then
        assertEquals(PlayResult.PICKUP, result);
        GameEvent event = onlyEvent();
        assertEquals(GameEventType.FAILED_PLAY, event.type());
        assertEquals(List.of(illegal), event.cards());
        assertEquals(2, event.count());
    }

    @Test
    void rejectedPlay_recordsNoEvent() {
        // Given
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        Card three = card(3);
        p1.getHand().add(three);
        session.getDiscardPile().add(card(9));

        // When
        PlayResult result = session.playCards(List.of(three));

        // Then
        assertEquals(PlayResult.INVALID, result);
        assertTrue(session.getEvents().isEmpty());
    }

    // =========================================================================
    // Burns, reverse, replay
    // =========================================================================

    @Test
    void fourOfAKindBurn_recordsPlayedThenBurnedWithPileSize() {
        // Given
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        for (Suit suit : List.of(Suit.HEARTS, Suit.DIAMONDS, Suit.CLUBS)) {
            session.getDiscardPile().add(Card.builder().suit(suit).value(7).rule(CardRule.DEFAULT).alwaysPlayable(false).build());
        }
        Card fourthSeven = cardWithRule(7, CardRule.DEFAULT);
        p1.getHand().add(fourthSeven);
        p1.getHand().add(card(4));

        // When
        session.playCards(List.of(fourthSeven));

        // Then
        assertEquals(List.of(GameEventType.PLAYED, GameEventType.BURNED, GameEventType.PLAYED_AGAIN), types());
        assertEquals(4, session.getEvents().get(1).count());
    }

    @Test
    void burnerCard_recordsBurnedAndPlayedAgain() {
        // Given
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        Card burner = cardWithRule(10, CardRule.BURNER);
        p1.getHand().add(burner);
        p1.getHand().add(card(3));
        session.getDiscardPile().add(card(5));

        // When
        session.playCards(List.of(burner));

        // Then
        assertEquals(List.of(GameEventType.PLAYED, GameEventType.BURNED, GameEventType.PLAYED_AGAIN), types());
        assertEquals(2, session.getEvents().get(1).count());
        assertEquals("p1", session.getEvents().get(2).playerId());
    }

    @Test
    void reverseCard_recordsReversedEvent() {
        // Given
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        Card reverse = cardWithRule(9, CardRule.REVERSE);
        p1.getHand().add(reverse);
        p1.getHand().add(card(3));

        // When
        session.playCards(List.of(reverse));

        // Then
        assertEquals(List.of(GameEventType.PLAYED, GameEventType.REVERSED), types());
    }

    // =========================================================================
    // Setup, out, game end
    // =========================================================================

    @Test
    void markReady_recordsReadyEvent() {
        // Given
        session.start();

        // When
        session.markReady("p1");

        // Then
        GameEvent event = onlyEvent();
        assertEquals(GameEventType.READY, event.type());
        assertEquals("alice", event.username());
    }

    @Test
    void playerGoingOut_recordsOutEventWithoutEndingThreePlayerGame() {
        // Given — three players, so one player going out does not end the game
        session.addPlayer("p3", "carol");
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        Card last = card(4);
        p1.getHand().add(last);
        session.getPlayers().get(1).getHand().add(card(13));
        session.getPlayers().get(2).getHand().add(card(13));

        // When
        session.playCards(List.of(last));

        // Then
        assertFalse(session.isFinished());
        assertEquals(List.of(GameEventType.PLAYED, GameEventType.OUT), types());
    }

    @Test
    void gameEnding_recordsFinishedEventNamingShithead() {
        // Given
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        Player p2 = session.getPlayers().get(1);
        Card last = card(4);
        p1.getHand().add(last);
        p2.getHand().add(card(13));

        // When
        session.playCards(List.of(last));

        // Then
        assertTrue(session.isFinished());
        assertEquals(List.of(GameEventType.PLAYED, GameEventType.OUT, GameEventType.FINISHED), types());
        GameEvent finished = session.getEvents().get(2);
        assertEquals("p2", finished.playerId());
        assertEquals("p2", session.getShitheadId());
    }

    // =========================================================================
    // Cap and sequencing
    // =========================================================================

    @Test
    void events_areCappedAtMostRecentThirty_withIncreasingSequence() {
        // Given
        prepareStartedGame();

        // When — 35 pickups, each one logs an event
        for (int i = 0; i < 35; i++) {
            session.getDiscardPile().add(card(5));
            session.pickupPile();
        }

        // Then
        assertEquals(GameSession.MAX_EVENTS, session.getEvents().size());
        assertEquals(30, session.getEvents().size());
        assertEquals(6L, session.getEvents().get(0).seq());
        assertEquals(35L, session.getEvents().get(29).seq());
        for (int i = 1; i < session.getEvents().size(); i++) {
            assertEquals(session.getEvents().get(i - 1).seq() + 1, session.getEvents().get(i).seq());
        }
    }
}
