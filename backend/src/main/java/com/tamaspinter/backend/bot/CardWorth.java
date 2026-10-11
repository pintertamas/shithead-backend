package com.tamaspinter.backend.bot;

import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.model.Suit;

/**
 * How much a card is worth keeping, under the rules of the game. Ordinary cards are worth their rank above the
 * lowest (a 2 that is ordinary is worth 0, an ace 12); special cards are worth more than any ordinary card, the
 * ones that clear or reset the pile the most.
 */
final class CardWorth {
    /** Burns the pile, or lets the player play again. */
    static final int CLEARING = 20;
    /** Always playable, and anything may follow it. */
    static final int JOKER = 18;
    /** Always playable (transparent and any other always-playable rank). */
    static final int ALWAYS_PLAYABLE = 16;
    /** Lowest worth that counts as a power card: cards worth this much are kept for when they are needed. */
    static final int POWER = ALWAYS_PLAYABLE;
    /** Extra worth of a 'smaller' card: it makes the next player go low. */
    private static final int SMALLER_BONUS = 3;
    /** Extra worth of a pile-resetting joker that is not always playable. */
    private static final int RESET_BONUS = 4;

    private CardWorth() {
    }

    static int of(Card card, GameConfig config) {
        if (card.getRule() == CardRule.BURNER || config.canPlayAgain(card.getValue())) {
            return CLEARING;
        }
        if (card.isAlwaysPlayable()) {
            return card.getRule() == CardRule.JOKER ? JOKER : ALWAYS_PLAYABLE;
        }
        int rank = card.getValue() - CardMemory.MIN_VALUE;
        return switch (card.getRule()) {
            case SMALLER -> rank + SMALLER_BONUS;
            case JOKER -> rank + RESET_BONUS;
            default -> rank;
        };
    }

    static boolean isPower(Card card, GameConfig config) {
        return of(card, config) >= POWER;
    }

    /** A card of this value under the game's rules; the suit never matters to the rules. */
    static Card cardOf(int value, GameConfig config) {
        return Card.builder()
                .suit(Suit.HEARTS)
                .value(value)
                .rule(config.getCardRule(value))
                .alwaysPlayable(config.isAlwaysPlayable(value))
                .build();
    }
}
