package com.tamaspinter.backend.bot;

import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.rules.RuleEngine;

import java.util.Deque;
import java.util.List;

/**
 * Estimates from public information only whether an opponent can play on a given pile.
 * <ul>
 *   <li>Hand phase: certain when a value they are known to hold fits; otherwise each unknown hand card is taken
 *       as a random draw from the cards the bot has not seen anywhere.</li>
 *   <li>Face-up phase: their playable cards are on the table, so the answer is exact.</li>
 *   <li>Face-down phase: one blind flip from the unseen cards.</li>
 * </ul>
 */
final class OpponentOdds {
    private final BotView view;
    private final GameConfig config;

    OpponentOdds(BotView view) {
        this.view = view;
        this.config = view.config();
    }

    /** Probability (0..1) that the opponent can play on this pile. */
    double canPlayChance(BotView.OpponentView opponent, Deque<Card> pile) {
        if (opponent.handCount() > 0) {
            List<Integer> known = view.knownHand(opponent.playerId());
            if (known.stream().anyMatch(value -> fits(value, pile))) {
                return 1;
            }
            int unknown = Math.max(0, opponent.handCount() - known.size());
            return 1 - Math.pow(1 - unseenFitShare(pile), unknown);
        }
        if (!opponent.faceUp().isEmpty()) {
            return opponent.faceUp().stream().anyMatch(card -> RuleEngine.canPlay(card, pile)) ? 1 : 0;
        }
        return opponent.faceDownCount() > 0 ? unseenFitShare(pile) : 1;
    }

    /**
     * Share of the unseen cards that could be played on the pile. Without any count (nothing unseen, or a bot
     * without memory) every value is taken as equally likely.
     */
    double unseenFitShare(Deque<Card> pile) {
        int fitting = 0;
        int total = 0;
        int fittingValues = 0;
        for (int value = CardMemory.MIN_VALUE; value <= CardMemory.MAX_VALUE; value++) {
            boolean fits = fits(value, pile);
            total += view.unseen(value);
            fitting += fits ? view.unseen(value) : 0;
            fittingValues += fits ? 1 : 0;
        }
        if (total == 0) {
            return fittingValues / (double) (CardMemory.MAX_VALUE - CardMemory.MIN_VALUE + 1);
        }
        return fitting / (double) total;
    }

    private boolean fits(int value, Deque<Card> pile) {
        return RuleEngine.canPlay(CardWorth.cardOf(value, config), pile);
    }
}
