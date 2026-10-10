package com.tamaspinter.backend.game;

import com.tamaspinter.backend.game.GameSession.InvalidReason;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.model.Deck;
import com.tamaspinter.backend.model.Player;
import com.tamaspinter.backend.model.Suit;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GameSessionInvalidReasonTest {

    /** One table row: arrange a session, run one call, expect INVALID with this reason (and pile value, if any). */
    private record Scenario(String name, Consumer<GameSession> arrange, Function<GameSession, PlayResult> act,
                            InvalidReason expectedReason, int expectedPileValue) {
    }

    private static Card card(int value, CardRule rule) {
        return Card.builder().suit(Suit.HEARTS).value(value).rule(rule).alwaysPlayable(false).build();
    }

    private static GameSession startedSession() {
        GameSession session = GameSession.builder().sessionId("reasons").build();
        session.addPlayer("p1", "alice");
        session.addPlayer("p2", "bob");
        session.setStarted(true);
        session.setSetupComplete(true);
        session.setConfig(GameConfig.defaultGameConfig());
        session.setDeck(new Deck(List.of()));
        return session;
    }

    private static Player alice(GameSession session) {
        return session.getPlayers().get(0);
    }

    private static CardSelection hand(int index) {
        return new CardSelection(CardSource.HAND, index);
    }

    private static CardSelection faceUp(int index) {
        return new CardSelection(CardSource.FACE_UP, index);
    }

    private static CardSelection faceDown(int index) {
        return new CardSelection(CardSource.FACE_DOWN, index);
    }

    private static GameConfig configWithMixedHandAndFaceUp() {
        GameConfig defaults = GameConfig.defaultGameConfig();
        return GameConfig.builder()
                .faceDownCount(3)
                .faceUpCount(3)
                .handCount(3)
                .burnCount(4)
                .allowMixedHandAndFaceUpWhenDeckEmpty(true)
                .cardRuleMap(defaults.getCardRuleMap())
                .alwaysPlayableMap(defaults.getAlwaysPlayableMap())
                .canPlayAgainMap(defaults.getCanPlayAgainMap())
                .build();
    }

    private static List<Scenario> playScenarios() {
        return List.of(
                new Scenario("setup not complete",
                        s -> s.setSetupComplete(false),
                        s -> s.playCards(List.of(card(5, CardRule.DEFAULT))),
                        InvalidReason.SETUP_NOT_COMPLETE, 0),
                new Scenario("game finished",
                        s -> s.setFinished(true),
                        s -> s.playCards(List.of(card(5, CardRule.DEFAULT))),
                        InvalidReason.GAME_FINISHED, 0),
                new Scenario("empty legacy card list",
                        s -> alice(s).getHand().add(card(5, CardRule.DEFAULT)),
                        s -> s.playCards(List.of()),
                        InvalidReason.EMPTY_SELECTION, 0),
                new Scenario("empty selection list",
                        s -> alice(s).getHand().add(card(5, CardRule.DEFAULT)),
                        s -> s.playSelections(List.of()),
                        InvalidReason.EMPTY_SELECTION, 0),
                new Scenario("legacy card not in hand",
                        s -> alice(s).getHand().add(card(5, CardRule.DEFAULT)),
                        s -> s.playCards(List.of(card(9, CardRule.DEFAULT))),
                        InvalidReason.CARD_NOT_AVAILABLE, 0),
                new Scenario("selection index out of range",
                        s -> alice(s).getHand().add(card(5, CardRule.DEFAULT)),
                        s -> s.playSelections(List.of(hand(3))),
                        InvalidReason.CARD_NOT_AVAILABLE, 0),
                new Scenario("same selection twice",
                        s -> alice(s).getHand().add(card(5, CardRule.DEFAULT)),
                        s -> s.playSelections(List.of(hand(0), hand(0))),
                        InvalidReason.CARD_NOT_AVAILABLE, 0),
                new Scenario("face-up while hand still has cards",
                        s -> {
                            alice(s).getHand().add(card(5, CardRule.DEFAULT));
                            alice(s).getFaceUp().add(card(9, CardRule.DEFAULT));
                        },
                        s -> s.playSelections(List.of(faceUp(0))),
                        InvalidReason.WRONG_ZONE, 0),
                new Scenario("face-down while hand still has cards",
                        s -> {
                            alice(s).getHand().add(card(5, CardRule.DEFAULT));
                            alice(s).getFaceDown().add(card(9, CardRule.DEFAULT));
                        },
                        s -> s.playSelections(List.of(faceDown(0))),
                        InvalidReason.WRONG_ZONE, 0),
                new Scenario("face-down while face-up still has cards",
                        s -> {
                            alice(s).getFaceUp().add(card(9, CardRule.DEFAULT));
                            alice(s).getFaceDown().add(card(4, CardRule.DEFAULT));
                        },
                        s -> s.playSelections(List.of(faceDown(0))),
                        InvalidReason.WRONG_ZONE, 0),
                new Scenario("two face-down cards at once",
                        s -> {
                            alice(s).getFaceDown().add(card(4, CardRule.DEFAULT));
                            alice(s).getFaceDown().add(card(6, CardRule.DEFAULT));
                        },
                        s -> s.playSelections(List.of(faceDown(0), faceDown(1))),
                        InvalidReason.FACE_DOWN_ONE_AT_A_TIME, 0),
                new Scenario("face-down mixed with hand",
                        s -> {
                            alice(s).getHand().add(card(5, CardRule.DEFAULT));
                            alice(s).getFaceDown().add(card(4, CardRule.DEFAULT));
                        },
                        s -> s.playSelections(List.of(hand(0), faceDown(0))),
                        InvalidReason.WRONG_ZONE, 0),
                new Scenario("different values played together",
                        s -> {
                            alice(s).getHand().add(card(5, CardRule.DEFAULT));
                            alice(s).getHand().add(card(6, CardRule.DEFAULT));
                        },
                        s -> s.playCards(List.of(card(5, CardRule.DEFAULT), card(6, CardRule.DEFAULT))),
                        InvalidReason.MIXED_VALUES, 0),
                new Scenario("too low on a default pile",
                        s -> {
                            s.getDiscardPile().add(card(9, CardRule.DEFAULT));
                            alice(s).getHand().add(card(5, CardRule.DEFAULT));
                        },
                        s -> s.playCards(List.of(card(5, CardRule.DEFAULT))),
                        InvalidReason.TOO_LOW, 9),
                new Scenario("too high on a smaller pile",
                        s -> {
                            s.getDiscardPile().add(card(9, CardRule.SMALLER));
                            alice(s).getHand().add(card(12, CardRule.DEFAULT));
                        },
                        s -> s.playCards(List.of(card(12, CardRule.DEFAULT))),
                        InvalidReason.TOO_HIGH, 9),
                new Scenario("too low through transparent card on default pile",
                        s -> {
                            s.getDiscardPile().add(card(9, CardRule.DEFAULT));
                            s.getDiscardPile().add(card(3, CardRule.TRANSPARENT));
                            alice(s).getHand().add(card(5, CardRule.DEFAULT));
                        },
                        s -> s.playSelections(List.of(hand(0))),
                        InvalidReason.TOO_LOW, 9),
                new Scenario("too high through transparent card on smaller pile",
                        s -> {
                            s.getDiscardPile().add(card(4, CardRule.SMALLER));
                            s.getDiscardPile().add(card(2, CardRule.TRANSPARENT));
                            alice(s).getHand().add(card(7, CardRule.DEFAULT));
                        },
                        s -> s.playSelections(List.of(hand(0))),
                        InvalidReason.TOO_HIGH, 4),
                new Scenario("too low from a hand selection",
                        s -> {
                            s.getDiscardPile().add(card(10, CardRule.DEFAULT));
                            alice(s).getHand().add(card(9, CardRule.DEFAULT));
                        },
                        s -> s.playSelections(List.of(hand(0))),
                        InvalidReason.TOO_LOW, 10),
                new Scenario("too high from a face-up selection with an empty hand",
                        s -> {
                            s.getDiscardPile().add(card(3, CardRule.SMALLER));
                            alice(s).getFaceUp().add(card(8, CardRule.DEFAULT));
                        },
                        s -> s.playSelections(List.of(faceUp(0))),
                        InvalidReason.TOO_HIGH, 3),
                new Scenario("mixed hand and face-up while the option is off",
                        s -> {
                            alice(s).getHand().add(card(9, CardRule.DEFAULT));
                            alice(s).getFaceUp().add(card(9, CardRule.DEFAULT));
                        },
                        s -> s.playSelections(List.of(hand(0), faceUp(0))),
                        InvalidReason.MIXED_HAND_FACEUP_NOT_ALLOWED, 0),
                new Scenario("mixed hand and face-up while the draw pile is not empty",
                        s -> {
                            s.setConfig(configWithMixedHandAndFaceUp());
                            s.setDeck(new Deck(List.of(card(2, CardRule.DEFAULT))));
                            alice(s).getHand().add(card(9, CardRule.DEFAULT));
                            alice(s).getFaceUp().add(card(9, CardRule.DEFAULT));
                        },
                        s -> s.playSelections(List.of(hand(0), faceUp(0))),
                        InvalidReason.MIXED_HAND_FACEUP_NOT_ALLOWED, 0),
                new Scenario("mixed hand and face-up with different values",
                        s -> {
                            s.setConfig(configWithMixedHandAndFaceUp());
                            alice(s).getHand().add(card(5, CardRule.DEFAULT));
                            alice(s).getFaceUp().add(card(6, CardRule.DEFAULT));
                        },
                        s -> s.playSelections(List.of(hand(0), faceUp(0))),
                        InvalidReason.MIXED_VALUES, 0)
        );
    }

    private static List<Scenario> pickupScenarios() {
        return List.of(
                new Scenario("pickup on an empty pile",
                        s -> { },
                        GameSession::pickupPile,
                        InvalidReason.PILE_EMPTY, 0),
                new Scenario("pickup after the game ended",
                        s -> {
                            s.getDiscardPile().add(card(5, CardRule.DEFAULT));
                            s.setFinished(true);
                        },
                        GameSession::pickupPile,
                        InvalidReason.GAME_FINISHED, 0),
                new Scenario("pickup before setup is complete",
                        s -> {
                            s.getDiscardPile().add(card(5, CardRule.DEFAULT));
                            s.setSetupComplete(false);
                        },
                        GameSession::pickupPile,
                        InvalidReason.SETUP_NOT_COMPLETE, 0)
        );
    }

    @TestFactory
    Stream<DynamicTest> everyInvalidPlayReportsItsReason() {
        return playScenarios().stream().map(this::asDynamicTest);
    }

    @TestFactory
    Stream<DynamicTest> everyInvalidPickupReportsItsReason() {
        return pickupScenarios().stream().map(this::asDynamicTest);
    }

    private DynamicTest asDynamicTest(Scenario scenario) {
        return DynamicTest.dynamicTest(scenario.name(), () -> {
            // Given
            GameSession session = startedSession();
            scenario.arrange().accept(session);

            // When
            PlayResult result = scenario.act().apply(session);

            // Then
            assertEquals(PlayResult.INVALID, result);
            assertEquals(scenario.expectedReason(), session.getLastInvalidReason());
            assertEquals(scenario.expectedPileValue(), session.getLastRequiredPileValue());
        });
    }

    @Test
    void validPlay_afterInvalidOne_clearsTheReason() {
        // Given — first a too-low play is rejected
        GameSession session = startedSession();
        session.getDiscardPile().add(card(9, CardRule.DEFAULT));
        alice(session).getHand().add(card(5, CardRule.DEFAULT));
        alice(session).getHand().add(card(10, CardRule.DEFAULT));
        assertEquals(PlayResult.INVALID, session.playCards(List.of(card(5, CardRule.DEFAULT))));
        assertEquals(InvalidReason.TOO_LOW, session.getLastInvalidReason());

        // When — the same player plays a legal card
        PlayResult result = session.playCards(List.of(card(10, CardRule.DEFAULT)));

        // Then
        assertEquals(PlayResult.SUCCESS, result);
        assertNull(session.getLastInvalidReason());
        assertEquals(0, session.getLastRequiredPileValue());
    }

    @Test
    void failedBlindFlip_isAPickupNotAnInvalidPlay() {
        // Given — a face-down card that cannot be played on a pile of 9s
        GameSession session = startedSession();
        session.getDiscardPile().add(card(9, CardRule.DEFAULT));
        alice(session).getFaceDown().add(card(4, CardRule.DEFAULT));

        // When
        PlayResult result = session.playSelections(List.of(faceDown(0)));

        // Then — the pile is picked up and no error reason is reported
        assertEquals(PlayResult.PICKUP, result);
        assertNull(session.getLastInvalidReason());
    }

    @Test
    void failedFaceUpPlay_withOptionOn_isAPickupNotAnInvalidPlay() {
        // Given
        GameSession session = startedSession();
        session.setConfig(GameConfig.builder()
                .faceDownCount(3)
                .faceUpCount(3)
                .handCount(3)
                .burnCount(4)
                .allowFailedFaceUpPlay(true)
                .cardRuleMap(GameConfig.defaultGameConfig().getCardRuleMap())
                .alwaysPlayableMap(GameConfig.defaultGameConfig().getAlwaysPlayableMap())
                .canPlayAgainMap(GameConfig.defaultGameConfig().getCanPlayAgainMap())
                .build());
        session.getDiscardPile().add(card(9, CardRule.DEFAULT));
        alice(session).getFaceUp().add(card(4, CardRule.DEFAULT));

        // When
        PlayResult result = session.playSelections(List.of(faceUp(0)));

        // Then
        assertEquals(PlayResult.PICKUP, result);
        assertNull(session.getLastInvalidReason());
    }
}
