import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.model.Suit;
import com.tamaspinter.backend.rules.DefaultRuleStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.*;

class DefaultRuleStrategyTest {

    private final DefaultRuleStrategy strategy = new DefaultRuleStrategy();

    @Test
    void testHigherValueIsPlayable() {
        // Given
        Card prev = new Card(Suit.HEARTS, 5, CardRule.DEFAULT, false);
        Card newCard = new Card(Suit.SPADES, 7, CardRule.DEFAULT, false);
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(prev);

        // When/Then
        assertTrue(strategy.canPlay(newCard, pile));
    }

    @Test
    void testEqualValueIsPlayable() {
        // Given
        Card prev = new Card(Suit.HEARTS, 7, CardRule.DEFAULT, false);
        Card newCard = new Card(Suit.CLUBS, 7, CardRule.DEFAULT, false);
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(prev);

        // When/Then
        assertTrue(strategy.canPlay(newCard, pile));
    }

    @Test
    void testLowerValueIsNotPlayable() {
        // Given
        Card prev = new Card(Suit.HEARTS, 9, CardRule.DEFAULT, false);
        Card newCard = new Card(Suit.SPADES, 5, CardRule.DEFAULT, false);
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(prev);

        // When/Then
        assertFalse(strategy.canPlay(newCard, pile));
    }

    @Test
    void testAlwaysPlayableIgnoresRule() {
        // Given — king on top, low card marked always-playable
        Card prev = new Card(Suit.HEARTS, 13, CardRule.DEFAULT, false);
        Card newCard = new Card(Suit.SPADES, 3, CardRule.DEFAULT, true);
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
