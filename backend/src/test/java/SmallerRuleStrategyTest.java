import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.model.Suit;
import com.tamaspinter.backend.rules.SmallerRuleStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.*;

class SmallerRuleStrategyTest {

    private final SmallerRuleStrategy strategy = new SmallerRuleStrategy();

    @Test
    void testLowerValueIsPlayable() {
        // Given
        Card prev = new Card(Suit.HEARTS, 9, CardRule.SMALLER, false);
        Card newCard = new Card(Suit.SPADES, 5, CardRule.DEFAULT, false);
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(prev);

        // When/Then
        assertTrue(strategy.canPlay(newCard, pile));
    }

    @Test
    void testEqualValueIsPlayable() {
        // Given
        Card prev = new Card(Suit.HEARTS, 6, CardRule.SMALLER, false);
        Card newCard = new Card(Suit.SPADES, 6, CardRule.SMALLER, false);
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(prev);

        // When/Then
        assertTrue(strategy.canPlay(newCard, pile));
    }

    @Test
    void testHigherValueIsNotPlayable() {
        // Given
        Card prev = new Card(Suit.HEARTS, 6, CardRule.SMALLER, false);
        Card newCard = new Card(Suit.SPADES, 9, CardRule.DEFAULT, false);
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(prev);

        // When/Then
        assertFalse(strategy.canPlay(newCard, pile));
    }

    @Test
    void testAlwaysPlayableIgnoresRule() {
        // Given — ace played after a 3 (would normally fail SMALLER rule)
        Card prev = new Card(Suit.HEARTS, 3, CardRule.SMALLER, false);
        Card newCard = new Card(Suit.SPADES, 14, CardRule.DEFAULT, true);
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(prev);

        // When/Then
        assertTrue(strategy.canPlay(newCard, pile));
    }

    @Test
    void testPlayableOnEmptyPile() {
        // Given
        Card newCard = new Card(Suit.HEARTS, 5, CardRule.DEFAULT, false);
        Deque<Card> pile = new ArrayDeque<>();

        // When/Then
        assertTrue(strategy.canPlay(newCard, pile));
    }
}
