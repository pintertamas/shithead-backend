import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.model.Player;
import com.tamaspinter.backend.model.Suit;
import com.tamaspinter.backend.rules.ReverseRuleStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReverseRuleStrategyTest {

    @Test
    void testAfterEffect() {
        // Given
        ReverseRuleStrategy strategy = new ReverseRuleStrategy();
        Deque<Card> pile = new ArrayDeque<>();
        pile.add(new Card(Suit.HEARTS, 2, CardRule.JOKER, true));
        pile.add(new Card(Suit.DIAMONDS, 3, CardRule.DEFAULT, false));
        pile.add(new Card(Suit.DIAMONDS, 3, CardRule.DEFAULT, false));
        pile.add(new Card(Suit.CLUBS, 4, CardRule.DEFAULT, false));
        pile.add(new Card(Suit.CLUBS, 9, CardRule.REVERSE, false));

        List<Player> players = new ArrayList<>();
        Player player1 = new Player("1", "test1");
        Player player2 = new Player("2", "test2");
        Player player3 = new Player("3", "test3");
        Player player4 = new Player("4", "test4");
        players.add(player1);
        players.add(player2);
        players.add(player3);
        players.add(player4);
        Player currentPlayer = players.get(1);

        // When
        strategy.afterEffect(pile, players, currentPlayer);

        // Then
        assertEquals("test4", players.get(0).getUsername());
        assertEquals("test3", players.get(1).getUsername());
        assertEquals("test2", players.get(2).getUsername());
        assertEquals("test1", players.get(3).getUsername());
    }
}