import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.model.Suit;
import com.tamaspinter.backend.rules.RuleEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.*;

class RuleEngineTest {

    @Test
    void testCanPlayOnEmptyPile() {
        // Given
        Card card = new Card(Suit.HEARTS, 7, CardRule.DEFAULT, false);
        Deque<Card> pile = new ArrayDeque<>();

        // When/Then
        assertTrue(RuleEngine.canPlay(card, pile));
    }

    @Test
    void testCanPlayAlwaysPlayableCard() {
        // Given
        Card alwaysPlayableCard = new Card(Suit.HEARTS, 7, CardRule.DEFAULT, true);
        Card topCard = new Card(Suit.SPADES, 10, CardRule.DEFAULT, false);
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(topCard);

        // When/Then
        assertTrue(RuleEngine.canPlay(alwaysPlayableCard, pile));
    }

    @Test
    void testShouldBurnWithFourSameValues() {
        // Given
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(new Card(Suit.HEARTS, 7, CardRule.DEFAULT, false));
        pile.add(new Card(Suit.DIAMONDS, 7, CardRule.DEFAULT, false));
        pile.add(new Card(Suit.CLUBS, 7, CardRule.DEFAULT, false));
        pile.add(new Card(Suit.SPADES, 7, CardRule.DEFAULT, false));

        // When/Then
        assertTrue(RuleEngine.shouldBurn(pile, 4));
    }

    @Test
    void testShouldNotBurnWithDifferentValues() {
        // Given
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(new Card(Suit.HEARTS, 7, CardRule.DEFAULT, false));
        pile.add(new Card(Suit.DIAMONDS, 8, CardRule.DEFAULT, false));
        pile.add(new Card(Suit.CLUBS, 9, CardRule.DEFAULT, false));
        pile.add(new Card(Suit.SPADES, 10, CardRule.DEFAULT, false));

        // When/Then
        assertFalse(RuleEngine.shouldBurn(pile, 4));
    }
}