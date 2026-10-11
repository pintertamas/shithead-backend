package com.tamaspinter.backend.bot;

import com.tamaspinter.backend.game.CardSelection;
import com.tamaspinter.backend.game.CardSource;
import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.model.Card;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * A card-counting bot that plays from public information only: the table, the values it saw go into opponents'
 * hands and how many cards of each value it has not seen ({@link BotView#knownHand}, {@link BotView#unseen}).
 * <ul>
 *   <li>Setup: puts its most valuable cards face up (clearing cards, jokers, always-playable cards, then high
 *       ranks) and prefers a pair face up, because face-up cards are played when the hand can no longer be
 *       refilled: strong cards there get it through that phase and a pair goes down in one play. Low cards stay in
 *       hand, where they are shed first while the draw pile still refills the hand.</li>
 *   <li>Play: scores every legal play with {@link PlayEvaluator}: it sheds cheap cards and all their copies, keeps
 *       power cards until they are worth it (burning a big pile, escaping when nothing else fits), and prefers
 *       leaving a pile the next player probably cannot play on, the more so the bigger the pile and the closer
 *       that player is to going out. It never picks up while it can play.</li>
 *   <li>Face-down: no information, so it flips the first card.</li>
 *   <li>Like the beginner, when its plays no longer draw cards it sometimes picks one of its best few plays at
 *       random, so two bots cannot trade the same pile back and forth forever.</li>
 * </ul>
 */
public class IntermediateBotStrategy implements BotStrategy {
    /** One in this many plays is picked at random among the best few while plays do not draw cards. */
    private static final int RANDOM_PLAY_ODDS = 7;
    /** How many of the best plays the random escape picks from. */
    private static final int RANDOM_PLAY_CHOICES = 3;
    /** Setup: worth added for every face-up card that has the value of another face-up card. */
    private static final double PAIR_BONUS = 3;
    /** Setup: tiny preference for keeping dealt positions among equally good layouts. */
    private static final double KEEP_DEALT = 0.01;
    /** Setup: above this many cards the layout is chosen greedily instead of trying every split. */
    private static final int MAX_EXHAUSTIVE_CARDS = 16;

    private final PlayEvaluator.Weights weights;

    public IntermediateBotStrategy() {
        this(PlayEvaluator.Weights.DEFAULT);
    }

    IntermediateBotStrategy(PlayEvaluator.Weights weights) {
        this.weights = weights;
    }

    @Override
    public SetupSwap chooseSetupSwap(List<Card> hand, List<Card> faceUp, GameConfig config) {
        if (hand.isEmpty() || faceUp.isEmpty()) {
            return SetupSwap.none();
        }
        List<Card> all = new ArrayList<>(hand);
        all.addAll(faceUp);
        long faceUpMask = all.size() <= MAX_EXHAUSTIVE_CARDS
                ? bestFaceUpMask(all, hand.size(), faceUp.size(), config)
                : greedyFaceUpMask(all, hand.size(), faceUp.size(), config);
        List<Integer> handIndices = IntStream.range(0, hand.size())
                .filter(i -> (faceUpMask & 1L << i) != 0).boxed().toList();
        List<Integer> faceUpIndices = IntStream.range(0, faceUp.size())
                .filter(i -> (faceUpMask & 1L << hand.size() + i) == 0).boxed().toList();
        return handIndices.isEmpty() ? SetupSwap.none() : new SetupSwap(handIndices, faceUpIndices);
    }

    /** Tries every way to put the face-up number of cards face up and keeps the best layout. */
    private static long bestFaceUpMask(List<Card> all, int handSize, int faceUpCount, GameConfig config) {
        long best = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (long mask = 0; mask < 1L << all.size(); mask++) {
            if (Long.bitCount(mask) != faceUpCount) {
                continue;
            }
            double score = layoutScore(all, mask, handSize, config);
            if (score > bestScore) {
                bestScore = score;
                best = mask;
            }
        }
        return best;
    }

    private static double layoutScore(List<Card> all, long mask, int handSize, GameConfig config) {
        double score = 0;
        List<Integer> faceUpValues = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if ((mask & 1L << i) == 0) {
                continue;
            }
            Card card = all.get(i);
            score += CardWorth.of(card, config) + (i >= handSize ? KEEP_DEALT : 0);
            score += faceUpValues.contains(card.getValue()) ? PAIR_BONUS : 0;
            faceUpValues.add(card.getValue());
        }
        return score;
    }

    /** The most valuable cards face up; ties keep the dealt position. */
    private static long greedyFaceUpMask(List<Card> all, int handSize, int faceUpCount, GameConfig config) {
        return IntStream.range(0, all.size()).boxed()
                .sorted(Comparator.comparingInt((Integer i) -> CardWorth.of(all.get(i), config)).reversed()
                        .thenComparing(i -> i >= handSize ? 0 : 1))
                .limit(faceUpCount)
                .mapToLong(i -> 1L << i)
                .reduce(0L, (left, right) -> left | right);
    }

    /**
     * The best scored legal play; null (pick up the pile) only when there is no legal play.
     */
    @Override
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    public List<CardSelection> choosePlay(BotView view, List<List<CardSelection>> legalPlays, Random random) {
        if (legalPlays.isEmpty()) {
            return null;
        }
        if (legalPlays.get(0).get(0).source() == CardSource.FACE_DOWN) {
            return legalPlays.get(0);
        }
        PlayEvaluator evaluator = new PlayEvaluator(view, weights);
        List<ScoredPlay> ranked = legalPlays.stream()
                .map(play -> new ScoredPlay(play, evaluator.score(play)))
                .sorted(Comparator.comparingDouble(ScoredPlay::score).reversed())
                .toList();
        boolean drawsNoCards = view.deckCount() == 0 || view.hand().size() > view.config().getHandCount();
        if (drawsNoCards && random.nextInt(RANDOM_PLAY_ODDS) == 0) {
            return ranked.get(random.nextInt(Math.min(RANDOM_PLAY_CHOICES, ranked.size()))).play();
        }
        return ranked.get(0).play();
    }

    private record ScoredPlay(List<CardSelection> play, double score) {
    }
}
