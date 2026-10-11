package com.tamaspinter.backend.bot;

import com.tamaspinter.backend.entity.BotMemoryEntity;
import com.tamaspinter.backend.game.CardSelection;
import com.tamaspinter.backend.game.CardSource;
import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.game.PlayResult;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.Player;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.tamaspinter.backend.bot.BotTables.card;
import static com.tamaspinter.backend.bot.BotTables.give;
import static com.tamaspinter.backend.bot.BotTables.human;
import static com.tamaspinter.backend.bot.BotTables.pile;
import static com.tamaspinter.backend.bot.BotTables.running;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardMemoryTest {

    private static final GameConfig DEFAULTS = GameConfig.defaultGameConfig();

    private final CardMemory memory = new CardMemory();

    /** A running two-player game observed by the memory; "p1" moves first. */
    private GameSession table() {
        GameSession session = running(DEFAULTS, human("p1"), human("p2"));
        session.setObserver(memory);
        return session;
    }

    private static Player seat(GameSession session, int index) {
        return session.getPlayers().get(index);
    }

    private static List<CardSelection> hand(int... indexes) {
        return java.util.Arrays.stream(indexes).mapToObj(index -> new CardSelection(CardSource.HAND, index)).toList();
    }

    private static List<Card> cards(int... values) {
        return java.util.Arrays.stream(values).mapToObj(value -> card(DEFAULTS, value)).toList();
    }

    @Test
    void pickup_remembersEveryPickedUpValue() {
        // Given
        GameSession session = table();
        give(session, seat(session, 0), 3);
        pile(session, 5, 11, 11);

        // When
        assertEquals(PlayResult.PICKUP, session.pickupPile());

        // Then
        assertEquals(List.of(5, 11, 11), memory.knownHand("p1"));
        assertEquals(List.of(), memory.knownHand("p2"));
    }

    @Test
    void play_forgetsPlayedKnownValues() {
        // Given: p1 picked up 5, 11, 11 and now holds 3, 5, 11, 11
        GameSession session = table();
        give(session, seat(session, 0), 3);
        pile(session, 5, 11, 11);
        session.pickupPile();
        session.setCurrentIndex(0);

        // When: p1 plays both jacks
        assertEquals(PlayResult.SUCCESS, session.playSelections(hand(2, 3)));

        // Then
        assertEquals(List.of(5), memory.knownHand("p1"));
    }

    @Test
    void play_ofUnknownCard_keepsKnownValues() {
        // Given
        GameSession session = table();
        give(session, seat(session, 0), 3, 4);
        pile(session, 12);
        session.pickupPile();
        session.setCurrentIndex(0);

        // When: p1 plays the 3 nobody saw
        assertEquals(PlayResult.SUCCESS, session.playSelections(hand(0)));

        // Then
        assertEquals(List.of(12), memory.knownHand("p1"));
    }

    @Test
    void failedBlindFlip_remembersTheRevealedCardWithThePile() {
        // Given
        GameSession session = table();
        seat(session, 0).getFaceDown().add(card(DEFAULTS, 4));
        pile(session, 13);

        // When
        PlayResult result = session.playSelections(List.of(new CardSelection(CardSource.FACE_DOWN, 0)));

        // Then
        assertEquals(PlayResult.PICKUP, result);
        assertEquals(List.of(4, 13), memory.knownHand("p1"));
    }

    @Test
    void burner_countsEveryBurnedValue() {
        // Given
        GameSession session = table();
        give(session, seat(session, 0), 10, 14);
        pile(session, 4, 4, 9);

        // When
        assertEquals(PlayResult.SUCCESS, session.playSelections(hand(0)));

        // Then
        assertEquals(2, memory.burnedCount(4));
        assertEquals(1, memory.burnedCount(9));
        assertEquals(1, memory.burnedCount(10));
        assertEquals(0, memory.burnedCount(14));
    }

    @Test
    void burnByCount_countsEveryBurnedValue() {
        // Given
        GameSession session = table();
        give(session, seat(session, 0), 7, 14);
        pile(session, 3, 7, 7, 7);

        // When
        assertEquals(PlayResult.SUCCESS, session.playSelections(hand(0)));

        // Then
        assertEquals(4, memory.burnedCount(7));
        assertEquals(1, memory.burnedCount(3));
        assertTrue(session.getDiscardPile().isEmpty());
    }

    @Test
    void burnedCount_outOfRangeValue_isZero() {
        // When / Then
        assertEquals(0, memory.burnedCount(1));
        assertEquals(0, memory.burnedCount(15));
    }

    @Test
    void setupSwap_remembersFaceUpCardsTakenIntoHand() {
        // Given
        GameSession session = table();
        session.setSetupComplete(false);
        seat(session, 0).setReady(false);
        give(session, seat(session, 0), 3, 4, 5);
        seat(session, 0).getFaceUp().addAll(cards(9, 12, 14));

        // When
        assertTrue(session.swapStartingCards("p1", List.of(0, 1), List.of(1, 2)));

        // Then
        assertEquals(List.of(12, 14), memory.knownHand("p1"));
    }

    @Test
    void setupSwap_swappedBackFaceUp_isForgotten() {
        // Given: p1 takes the ace into the hand, then puts it back face up
        GameSession session = table();
        session.setSetupComplete(false);
        seat(session, 0).setReady(false);
        give(session, seat(session, 0), 3, 4, 5);
        seat(session, 0).getFaceUp().addAll(cards(9, 12, 14));
        session.swapStartingCards("p1", List.of(0), List.of(2));

        // When: the hand is now 4, 5, 14 and the face-up cards 3, 9, 12
        assertTrue(session.swapStartingCards("p1", List.of(2), List.of(0)));

        // Then: the 3 came back into the hand; the ace is face up again, not in the hand
        assertEquals(List.of(3), memory.knownHand("p1"));
    }

    @Test
    void play_knownListLongerThanHand_isClampedToHandSize() {
        // Given: a memory that believes more than the hand can hold (e.g. restored from an older save)
        Player player = human("p1");
        player.getHand().addAll(cards(3, 5));
        memory.cardsPickedUp(player, cards(5, 6, 7, 8));

        // When
        memory.cardsPlayed(player, cards(9));

        // Then
        assertEquals(2, memory.knownHand("p1").size());
    }

    @Test
    void play_lastKnownValue_dropsThePlayer() {
        // Given
        Player player = human("p1");
        memory.cardsPickedUp(player, cards(5));

        // When
        memory.cardsPlayed(player, cards(5));

        // Then
        assertEquals(Map.of(), memory.toEntity().getKnownHands());
    }

    @Test
    void entity_roundTrip_keepsKnownHandsAndBurns() {
        // Given
        memory.cardsPickedUp(human("p1"), cards(3, 11, 11));
        memory.pileBurned(cards(4, 4, 10));

        // When
        BotMemoryEntity entity = memory.toEntity();
        CardMemory restored = CardMemory.fromEntity(entity);

        // Then
        assertEquals("3,11,11", entity.getKnownHands().get("p1"));
        assertEquals("0,0,2,0,0,0,0,0,1,0,0,0,0", entity.getBurned());
        assertEquals(List.of(3, 11, 11), restored.knownHand("p1"));
        assertEquals(2, restored.burnedCount(4));
        assertEquals(1, restored.burnedCount(10));
    }

    @Test
    void fromEntity_nullEntity_isEmpty() {
        // When
        CardMemory restored = CardMemory.fromEntity(null);

        // Then
        assertEquals(List.of(), restored.knownHand("p1"));
        assertEquals(0, restored.burnedCount(4));
    }

    @Test
    void fromEntity_missingFields_isEmpty() {
        // When
        CardMemory restored = CardMemory.fromEntity(new BotMemoryEntity());

        // Then
        assertEquals(List.of(), restored.knownHand("p1"));
        assertEquals(0, restored.burnedCount(4));
    }

    @Test
    void fromEntity_malformedValues_areIgnored() {
        // Given
        Map<String, String> hands = new HashMap<>();
        hands.put("p1", "3,x,5");
        hands.put("p2", "");
        hands.put("p3", "7, 8");
        BotMemoryEntity entity = BotMemoryEntity.builder().knownHands(hands).burned("1,2,oops").build();

        // When
        CardMemory restored = CardMemory.fromEntity(entity);

        // Then
        assertEquals(List.of(), restored.knownHand("p1"));
        assertEquals(List.of(), restored.knownHand("p2"));
        assertEquals(List.of(7, 8), restored.knownHand("p3"));
        assertEquals(0, restored.burnedCount(2));
        assertEquals(0, restored.burnedCount(3));
    }

    @Test
    void fromEntity_tooManyBurnCounts_ignoresTheRest() {
        // Given
        BotMemoryEntity entity = BotMemoryEntity.builder().burned("1,1,1,1,1,1,1,1,1,1,1,1,1,9,9").build();

        // When
        CardMemory restored = CardMemory.fromEntity(entity);

        // Then
        assertEquals(1, restored.burnedCount(2));
        assertEquals(1, restored.burnedCount(14));
    }
}
