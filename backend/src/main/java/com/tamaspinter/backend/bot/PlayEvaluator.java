package com.tamaspinter.backend.bot;

import com.tamaspinter.backend.game.CardSelection;
import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.rules.RuleEngine;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Scores one legal play from the bot's public view; higher is better. A play earns points for every card it sheds
 * and pays what the cards were worth keeping ({@link CardWorth}). A play that burns the pile earns points for every
 * card it removes. Otherwise the play is judged by the pile it leaves to the next player: the likelier they cannot
 * play on it ({@link OpponentOdds}), the bigger the pile and the closer they are to going out, the better, less the
 * power cards they would pick up with it.
 */
final class PlayEvaluator {

    private final BotView view;
    private final GameConfig config;
    private final OpponentOdds odds;
    private final Weights weights;

    /**
     * The weights of the score, tuned in seeded simulations against the beginner.
     *
     * @param shed         points per card played while the draw pile still refills the hand
     * @param shedEndgame  points per card played once the draw pile is empty
     * @param burnPerCard  points per pile card a burn removes
     * @param playAgain    points for keeping the turn (burn or play-again rank)
     * @param pressure     points per pile card the next player is expected to pick up
     * @param giftPerPower power cards in that pile count this many cards less, as they help whoever picks them up
     * @param urgency      with the draw pile empty, pressure grows by this divided by the next player's cards left
     * @param goOut        points for a play that leaves the bot without cards
     */
    record Weights(double shed, double shedEndgame, double burnPerCard, double playAgain, double pressure,
                   double giftPerPower, double urgency, double goOut) {
        static final Weights DEFAULT = new Weights(6, 9, 1.5, 3, 1, 1, 6, 50);
    }

    PlayEvaluator(BotView view, Weights weights) {
        this.view = view;
        this.config = view.config();
        this.odds = new OpponentOdds(view);
        this.weights = weights;
    }

    double score(List<CardSelection> play) {
        List<Card> cards = play.stream().map(this::cardOf).toList();
        Card lead = cards.get(0);
        Deque<Card> pileAfter = new ArrayDeque<>(view.pile());
        cards.forEach(pileAfter::addLast);
        double score = cards.stream().mapToDouble(card -> shedValue() - CardWorth.of(card, config)).sum();
        if (goesOut(cards.size())) {
            score += weights.goOut();
        }
        if (lead.getRule() == CardRule.BURNER || RuleEngine.shouldBurn(pileAfter, config.getBurnCount())) {
            return score + weights.burnPerCard() * view.pile().size() + weights.playAgain();
        }
        if (config.canPlayAgain(lead.getValue())) {
            return score + weights.playAgain();
        }
        return score + pressure(lead, pileAfter);
    }

    private double shedValue() {
        return view.deckCount() > 0 ? weights.shed() : weights.shedEndgame();
    }

    private boolean goesOut(int played) {
        int left = view.hand().size() + view.faceUp().size() + view.faceDownCount() - played;
        return left == 0 && view.deckCount() == 0;
    }

    /** What leaving this pile to the next player is worth: the expected value of them having to pick it up. */
    private double pressure(Card lead, Deque<Card> pileAfter) {
        BotView.OpponentView next = nextOpponent(lead.getRule() == CardRule.REVERSE);
        if (next == null) {
            return 0;
        }
        double stuck = 1 - odds.canPlayChance(next, pileAfter);
        long powerCards = pileAfter.stream().filter(card -> CardWorth.isPower(card, config)).count();
        double pickupWorth = Math.max(0, pileAfter.size() - weights.giftPerPower() * powerCards);
        return stuck * pickupWorth * weights.pressure() * urgency(next);
    }

    /** Grows as the opponent nears going out: no cards left to draw and few cards in front of them. */
    private double urgency(BotView.OpponentView opponent) {
        if (view.deckCount() > 0) {
            return 1;
        }
        int cardsLeft = opponent.handCount() + opponent.faceUp().size() + opponent.faceDownCount();
        return 1 + weights.urgency() / Math.max(1, cardsLeft);
    }

    /**
     * Who moves after a play that passes the turn. Opponents are listed in seat order from the bot, so it is the
     * first one still in, or after a reverse the last one still in.
     */
    private BotView.OpponentView nextOpponent(boolean reverses) {
        List<BotView.OpponentView> active = view.opponents().stream().filter(opponent -> !opponent.out()).toList();
        if (active.isEmpty()) {
            return null;
        }
        return reverses ? active.get(active.size() - 1) : active.get(0);
    }

    private Card cardOf(CardSelection selection) {
        return switch (selection.source()) {
            case HAND -> view.hand().get(selection.index());
            case FACE_UP -> view.faceUp().get(selection.index());
            case FACE_DOWN -> throw new IllegalArgumentException("Face-down cards are hidden from bots");
        };
    }
}
