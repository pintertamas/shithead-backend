package com.tamaspinter.backend.game;

import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.model.Deck;
import com.tamaspinter.backend.model.Player;
import com.tamaspinter.backend.model.Suit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class GameSessionTest {

    private GameSession session;

    @BeforeEach
    void setUp() {
        session = GameSession.builder().sessionId("test-session").build();
        session.addPlayer("p1", "alice");
        session.addPlayer("p2", "bob");
    }

    /** Creates a card with the correct rule and alwaysPlayable flag from the default config. */
    private Card card(int value) {
        GameConfig config = GameConfig.defaultGameConfig();
        return Card.builder()
                .suit(Suit.HEARTS)
                .value(value)
                .rule(config.getCardRule(value))
                .alwaysPlayable(config.isAlwaysPlayable(value))
                .build();
    }

    /** Sets the session into a started state with an empty deck (no hand refills). */
    private void prepareStartedGame() {
        session.setStarted(true);
        session.setDeck(new Deck(List.of()));
    }

    // =========================================================================
    // start()
    // =========================================================================

    @Test
    void testStart_dealsThreeCardsToEachZone() {
        // When
        session.start();

        // Then — each player receives exactly 3 cards per zone
        for (Player p : session.getPlayers()) {
            assertEquals(3, p.getFaceDown().size());
            assertEquals(3, p.getFaceUp().size());
            assertEquals(3, p.getHand().size());
        }
    }

    @Test
    void testStart_setsStartedFlag() {
        session.start();
        assertTrue(session.isStarted());
    }

    @Test
    void testAddPlayer_afterStart_throwsException() {
        session.start();
        assertThrows(IllegalStateException.class, () -> session.addPlayer("p3", "carol"));
    }

    // =========================================================================
    // playFromHand
    // =========================================================================

    @Test
    void testPlayFromHand_validCard_returnsSuccess() {
        // Given
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        Card playable = card(7);
        p1.getHand().add(playable);
        session.getDiscardPile().add(card(5));

        // When
        PlayResult result = session.playCards(List.of(playable));

        // Then
        assertEquals(PlayResult.SUCCESS, result);
        assertTrue(p1.getHand().isEmpty());
    }

    @Test
    void testPlayFromHand_multipleCardsOfSameValue_allMoveToPile() {
        // Given
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        Card c1 = Card.builder().suit(Suit.HEARTS).value(7).rule(CardRule.DEFAULT).alwaysPlayable(false).build();
        Card c2 = Card.builder().suit(Suit.SPADES).value(7).rule(CardRule.DEFAULT).alwaysPlayable(false).build();
        p1.getHand().add(c1);
        p1.getHand().add(c2);
        session.getDiscardPile().add(card(5));

        // When
        PlayResult result = session.playCards(List.of(c1, c2));

        // Then — both sevens join the existing 5 on the pile
        assertEquals(PlayResult.SUCCESS, result);
        assertEquals(3, session.getDiscardPile().size());
    }

    @Test
    void testPlayFromHand_lowerCardOnHigher_returnsInvalid() {
        // Given
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        Card lowCard = card(3);
        p1.getHand().add(lowCard);
        session.getDiscardPile().add(card(9));

        // When
        PlayResult result = session.playCards(List.of(lowCard));

        // Then — card stays in hand
        assertEquals(PlayResult.INVALID, result);
        assertEquals(1, p1.getHand().size());
    }

    @Test
    void testPlayFromHand_mixedValues_returnsInvalid() {
        // Given
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        Card c1 = card(5);
        Card c2 = card(7);
        p1.getHand().add(c1);
        p1.getHand().add(c2);

        // When
        PlayResult result = session.playCards(List.of(c1, c2));

        // Then
        assertEquals(PlayResult.INVALID, result);
    }

    @Test
    void testPlayFromHand_cardNotInHand_returnsInvalid() {
        // Given — hand is non-empty so routing goes through playFromHand,
        //          but the selected card is not actually in the hand
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        p1.getHand().add(card(5));
        Card notInHand = card(7);

        // When
        PlayResult result = session.playCards(List.of(notInHand));

        // Then
        assertEquals(PlayResult.INVALID, result);
    }

    @Test
    void testPlayFromHand_advancesToNextPlayer() {
        // Given
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        Card playable = card(7);
        p1.getHand().add(playable);
        session.getDiscardPile().add(card(5));

        // When
        assertEquals("p1", session.getCurrentPlayerId());
        session.playCards(List.of(playable));

        // Then
        assertEquals("p2", session.getCurrentPlayerId());
    }

    // =========================================================================
    // pickupPile
    // =========================================================================

    @Test
    void testPickupPile_movesCardsToPlayerHand_andClearsPile() {
        // Given
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        Card c1 = card(5);
        Card c2 = card(9);
        session.getDiscardPile().add(c1);
        session.getDiscardPile().add(c2);

        // When
        PlayResult result = session.pickupPile();

        // Then
        assertEquals(PlayResult.PICKUP, result);
        assertEquals(0, session.getDiscardPile().size());
        assertTrue(p1.getHand().contains(c1));
        assertTrue(p1.getHand().contains(c2));
    }

    @Test
    void testPickupPile_advancesToNextPlayer() {
        // Given
        prepareStartedGame();
        session.getDiscardPile().add(card(5));

        // When
        assertEquals("p1", session.getCurrentPlayerId());
        session.pickupPile();

        // Then
        assertEquals("p2", session.getCurrentPlayerId());
    }

    // =========================================================================
    // playFromFaceUp
    // =========================================================================

    @Test
    void testPlayFromFaceUp_whenHandEmpty_returnsSuccess() {
        // Given
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        Card faceUpCard = card(7);
        p1.getFaceUp().add(faceUpCard);
        p1.getFaceDown().add(card(5)); // keep p1 from going out
        session.getDiscardPile().add(card(5));

        // When
        PlayResult result = session.playCards(List.of(faceUpCard));

        // Then
        assertEquals(PlayResult.SUCCESS, result);
        assertTrue(p1.getFaceUp().isEmpty());
    }

    @Test
    void testPlayFromFaceUp_whenHandNotEmpty_returnsInvalid() {
        // Given — playCards dispatches to playFromHand first;
        //          the faceUp card is not in hand so returns INVALID
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        p1.getHand().add(card(5));
        Card faceUpCard = card(7);
        p1.getFaceUp().add(faceUpCard);

        // When
        PlayResult result = session.playCards(List.of(faceUpCard));

        // Then
        assertEquals(PlayResult.INVALID, result);
    }

    // =========================================================================
    // playFromFaceDown
    // =========================================================================

    @Test
    void testPlayFromFaceDown_successfulBlindFlip_returnsSuccess() {
        // Given
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        Card blind1 = Card.builder().suit(Suit.CLUBS).value(7).rule(CardRule.DEFAULT).alwaysPlayable(false).build(); // 7 >= 5, playable
        Card blind2 = Card.builder().suit(Suit.SPADES).value(9).rule(CardRule.DEFAULT).alwaysPlayable(false).build(); // kept so p1 is not out
        p1.getFaceDown().add(blind1);
        p1.getFaceDown().add(blind2);
        session.getDiscardPile().add(card(5));

        // When
        PlayResult result = session.playCards(List.of(blind1));

        // Then
        assertEquals(PlayResult.SUCCESS, result);
        assertEquals(1, p1.getFaceDown().size()); // blind2 still there
    }

    @Test
    void testPlayFromFaceDown_failedBlindFlip_returnsPickup() {
        // Given — flipped card cannot be played on top card
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        Card blindCard = card(3); // 3 < 9, fails DEFAULT rule
        p1.getFaceDown().add(blindCard);
        session.getDiscardPile().add(card(9));

        // When
        PlayResult result = session.playCards(List.of(blindCard));

        // Then
        assertEquals(PlayResult.PICKUP, result);
    }

    @Test
    void testPlayFromFaceDown_failedBlindFlip_playerReceivesPileAndFlippedCard() {
        // Given
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        Card blindCard = card(3);
        p1.getFaceDown().add(blindCard);
        Card pileCard = card(9);
        session.getDiscardPile().add(pileCard);

        // When
        session.playCards(List.of(blindCard));

        // Then — flipped card and entire pile move to hand; pile is cleared
        assertTrue(p1.getHand().contains(blindCard));
        assertTrue(p1.getHand().contains(pileCard));
        assertEquals(0, session.getDiscardPile().size());
        assertTrue(p1.getFaceDown().isEmpty());
    }

    // =========================================================================
    // Special card effects
    // =========================================================================

    @Test
    void testFourOfAKindBurn_clearsPile() {
        // Given — three 7s already on pile, player plays the fourth
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        session.getDiscardPile().add(Card.builder().suit(Suit.HEARTS).value(7).rule(CardRule.DEFAULT).alwaysPlayable(false).build());
        session.getDiscardPile().add(Card.builder().suit(Suit.DIAMONDS).value(7).rule(CardRule.DEFAULT).alwaysPlayable(false).build());
        session.getDiscardPile().add(Card.builder().suit(Suit.CLUBS).value(7).rule(CardRule.DEFAULT).alwaysPlayable(false).build());
        Card fourthSeven = Card.builder().suit(Suit.SPADES).value(7).rule(CardRule.DEFAULT).alwaysPlayable(false).build();
        p1.getHand().add(fourthSeven);
        p1.getHand().add(card(4)); // extra card so p1 is not out after playing

        // When
        PlayResult result = session.playCards(List.of(fourthSeven));

        // Then
        assertEquals(PlayResult.SUCCESS, result);
        assertEquals(0, session.getDiscardPile().size());
    }

    @Test
    void testBurnerCard_clearsAndGrantsReplay() {
        // Given
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        Card burner = Card.builder().suit(Suit.CLUBS).value(10).rule(CardRule.BURNER).alwaysPlayable(false).build();
        p1.getHand().add(burner);
        p1.getHand().add(card(3)); // extra so p1 is not out after playing
        session.getDiscardPile().add(card(5));

        // When
        assertEquals("p1", session.getCurrentPlayerId());
        PlayResult result = session.playCards(List.of(burner));

        // Then — pile cleared, same player's turn
        assertEquals(PlayResult.SUCCESS, result);
        assertEquals(0, session.getDiscardPile().size());
        assertEquals("p1", session.getCurrentPlayerId());
    }

    @Test
    void testBurnerCard_playerOut_turnAdvancesDespiteReplay() {
        // Given — burner is the last card; player goes out, so turn must advance
        //          even though BURNER normally grants replay
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        Card burner = Card.builder().suit(Suit.CLUBS).value(10).rule(CardRule.BURNER).alwaysPlayable(false).build();
        p1.getHand().add(burner);
        session.getPlayers().get(1).getHand().add(card(5)); // p2 has cards → becomes shithead

        // When
        session.playCards(List.of(burner));

        // Then
        assertTrue(p1.isOut());
        assertEquals("p2", session.getCurrentPlayerId());
    }

    @Test
    void testReverseCard_reversesPlayerOrderAndAdvancesTurn() {
        // Given — 3-player game so the order change is observable
        GameSession s = GameSession.builder().sessionId("reverse-session").build();
        s.addPlayer("p1", "alice");
        s.addPlayer("p2", "bob");
        s.addPlayer("p3", "carol");
        s.setStarted(true);
        s.setDeck(new Deck(List.of()));
        Player alice = s.getPlayers().get(0);
        Card reverseCard = Card.builder().suit(Suit.CLUBS).value(9).rule(CardRule.REVERSE).alwaysPlayable(false).build();
        alice.getHand().add(reverseCard);
        alice.getHand().add(card(5)); // extra so alice is not out after playing

        // When
        s.playCards(List.of(reverseCard));

        // Then — list reversed to [carol, bob, alice]; currentIndex was 0,
        //         nextPlayer() advances to index 1 → bob's turn
        assertEquals("carol", s.getPlayers().get(0).getUsername());
        assertEquals("bob",   s.getPlayers().get(1).getUsername());
        assertEquals("alice", s.getPlayers().get(2).getUsername());
        assertEquals("p2", s.getCurrentPlayerId());
    }

    // =========================================================================
    // Hand refill
    // =========================================================================

    @Test
    void testHandRefillsFromDeck_afterPlay() {
        // Given — player has 1 card in hand (below handCount of 3);
        //          mock deck delivers 2 refills then nothing
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        Card playable = card(7);
        p1.getHand().add(playable);

        Card refill1 = Card.builder().suit(Suit.CLUBS).value(3).rule(CardRule.DEFAULT).alwaysPlayable(false).build();
        Card refill2 = Card.builder().suit(Suit.DIAMONDS).value(4).rule(CardRule.DEFAULT).alwaysPlayable(false).build();
        Deck mockDeck = Mockito.mock(Deck.class);
        when(mockDeck.draw()).thenReturn(
                Optional.of(refill1),
                Optional.of(refill2),
                Optional.empty()
        );
        session.setDeck(mockDeck);
        session.getDiscardPile().add(card(5));

        // When
        session.playCards(List.of(playable));

        // Then — hand refilled from 0 to 2 (deck had only 2 cards)
        assertEquals(2, p1.getHand().size());
        assertTrue(p1.getHand().contains(refill1));
        assertTrue(p1.getHand().contains(refill2));
    }

    // =========================================================================
    // Player out / game end
    // =========================================================================

    @Test
    void testPlayerBecomesOut_whenAllCardsGone() {
        // Given — last card in hand, empty deck, no faceUp/faceDown
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        Card lastCard = card(7);
        p1.getHand().add(lastCard);
        session.getDiscardPile().add(card(5));

        // When
        session.playCards(List.of(lastCard));

        // Then
        assertTrue(p1.isOut());
    }

    @Test
    void testNextPlayer_skipsOutPlayers() {
        // Given — p2 (bob) is already out; after p1 plays, turn jumps to p3 (carol)
        GameSession s = GameSession.builder().sessionId("skip-session").build();
        s.addPlayer("p1", "alice");
        s.addPlayer("p2", "bob");
        s.addPlayer("p3", "carol");
        s.setStarted(true);
        s.setDeck(new Deck(List.of()));

        s.getPlayers().get(1).setOut(true); // bob is out
        Player alice = s.getPlayers().get(0);
        Card playable = card(7);
        alice.getHand().add(playable);
        alice.getHand().add(card(5)); // extra so alice is not out after playing
        s.getDiscardPile().add(card(5));

        // When
        s.playCards(List.of(playable));

        // Then — skipped bob, landed on carol
        assertEquals("p3", s.getCurrentPlayerId());
    }

    @Test
    void testGameEnds_whenOnePlayerRemains_andShitheadIsSet() {
        // Given — p1 plays their last card; only p2 remains → p2 is the shithead
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        session.getPlayers().get(1).getHand().add(card(4)); // p2 still has cards

        Card lastCard = card(7);
        p1.getHand().add(lastCard);
        session.getDiscardPile().add(card(5));

        // When
        session.playCards(List.of(lastCard));

        // Then
        assertTrue(session.isFinished());
        assertEquals("p2", session.getShitheadId());
    }

    // =========================================================================
    // Finished guard
    // =========================================================================

    @Test
    void testPlayCards_afterGameFinished_returnsInvalid() {
        // Given
        prepareStartedGame();
        session.setFinished(true);

        // When/Then
        assertEquals(PlayResult.INVALID, session.playCards(List.of(card(7))));
    }

    @Test
    void testPickupPile_afterGameFinished_returnsInvalid() {
        // Given
        prepareStartedGame();
        session.setFinished(true);

        // When/Then
        assertEquals(PlayResult.INVALID, session.pickupPile());
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    void testAlwaysPlayableCard_canBePlayedOnHighPile() {
        // Given — JOKER (value 2, alwaysPlayable=true) on top of a king
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        Card joker = card(2); // alwaysPlayable=true per default config
        p1.getHand().add(joker);
        session.getDiscardPile().add(card(13));

        // When/Then — should succeed even though 2 < 13
        assertEquals(PlayResult.SUCCESS, session.playCards(List.of(joker)));
    }

    @Test
    void testSmallerCard_onPileTop_enforcesDescendingRule() {
        // Given — pile topped with a 6 (SMALLER); next card must be <= 6
        prepareStartedGame();
        Player p1 = session.getPlayers().get(0);
        session.getDiscardPile().add(Card.builder().suit(Suit.CLUBS).value(6).rule(CardRule.SMALLER).alwaysPlayable(false).build());
        Card valid   = Card.builder().suit(Suit.HEARTS).value(4).rule(CardRule.DEFAULT).alwaysPlayable(false).build(); // 4 <= 6
        Card invalid = Card.builder().suit(Suit.HEARTS).value(9).rule(CardRule.DEFAULT).alwaysPlayable(false).build(); // 9 > 6
        p1.getHand().add(valid);
        p1.getHand().add(invalid);

        // When/Then — invalid move leaves turn unchanged; valid move succeeds
        assertEquals(PlayResult.INVALID, session.playCards(List.of(invalid)));
        assertEquals(PlayResult.SUCCESS,  session.playCards(List.of(valid)));
    }

    @Test
    void testGetCurrentPlayerId_returnsNullWhenNoPlayers() {
        // Given — fresh session with no players added
        GameSession empty = GameSession.builder().sessionId("empty").build();

        // When/Then
        assertNull(empty.getCurrentPlayerId());
    }
}

