package com.tamaspinter.backend.bot;

import com.tamaspinter.backend.game.CardSelection;
import com.tamaspinter.backend.game.CardSource;
import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * A simple bot that only looks at the table as it is now and remembers nothing.
 * <ul>
 *   <li>Setup: puts its strongest cards (special cards first, then high values) face up for later.</li>
 *   <li>Play: plays the lowest ordinary value it can, all copies of it at once; special cards only when nothing
 *       ordinary fits, one at a time and the least valuable first. It never picks up while it can play.</li>
 *   <li>Face-down: flips the first card.</li>
 * </ul>
 */
public class BeginnerBotStrategy implements BotStrategy {
    private static final int SPECIAL_BASE = 100;

    @Override
    public SetupSwap chooseSetupSwap(List<Card> hand, List<Card> faceUp, GameConfig config) {
        if (hand.isEmpty() || faceUp.isEmpty()) {
            return SetupSwap.none();
        }
        List<Card> all = new ArrayList<>(hand);
        all.addAll(faceUp);
        // The faceUp.size() strongest cards should end up face up; ties keep the dealt position.
        List<Integer> strongest = IntStream.range(0, all.size()).boxed()
                .sorted(Comparator.comparingInt((Integer i) -> setupStrength(all.get(i), config)).reversed()
                        .thenComparing(i -> i >= hand.size() ? 0 : 1))
                .limit(faceUp.size())
                .toList();
        List<Integer> handIndices = new ArrayList<>();
        List<Integer> faceUpIndices = new ArrayList<>();
        for (int i = 0; i < hand.size(); i++) {
            if (strongest.contains(i)) {
                handIndices.add(i);
            }
        }
        for (int i = 0; i < faceUp.size(); i++) {
            if (!strongest.contains(hand.size() + i)) {
                faceUpIndices.add(i);
            }
        }
        return handIndices.isEmpty() ? SetupSwap.none() : new SetupSwap(handIndices, faceUpIndices);
    }

    @Override
    public List<CardSelection> choosePlay(BotView view, List<List<CardSelection>> legalPlays) {
        List<List<CardSelection>> singleZone = legalPlays.stream()
                .filter(play -> play.stream().map(CardSelection::source).distinct().count() == 1)
                .toList();
        if (singleZone.isEmpty()) {
            return legalPlays.isEmpty() ? null : legalPlays.get(0);
        }
        if (singleZone.get(0).get(0).source() == CardSource.FACE_DOWN) {
            return singleZone.get(0);
        }
        Comparator<List<CardSelection>> preference = Comparator
                .comparingInt((List<CardSelection> play) -> playCost(cardOf(view, play.get(0)), view.config()))
                .thenComparing(play -> isSpecial(cardOf(view, play.get(0)), view.config())
                        ? play.size() : -play.size());
        return singleZone.stream().min(preference).orElse(null);
    }

    /** Lower is played first: ordinary cards by value, then special cards from the least to the most valuable. */
    private static int playCost(Card card, GameConfig config) {
        return isSpecial(card, config) ? SPECIAL_BASE + specialRank(card, config) : card.getValue();
    }

    /** Higher goes face up during setup: special cards above every ordinary card, then by value. */
    private static int setupStrength(Card card, GameConfig config) {
        return playCost(card, config);
    }

    static boolean isSpecial(Card card, GameConfig config) {
        return card.getRule() != CardRule.DEFAULT || card.isAlwaysPlayable() || config.canPlayAgain(card.getValue());
    }

    /** How much a special card is worth keeping: burning and always-playable cards are kept the longest. */
    private static int specialRank(Card card, GameConfig config) {
        if (card.getRule() == CardRule.BURNER || config.canPlayAgain(card.getValue())) {
            return 4;
        }
        if (card.isAlwaysPlayable()) {
            return card.getRule() == CardRule.JOKER ? 3 : 2;
        }
        return 1;
    }

    private static Card cardOf(BotView view, CardSelection selection) {
        return switch (selection.source()) {
            case HAND -> view.hand().get(selection.index());
            case FACE_UP -> view.faceUp().get(selection.index());
            case FACE_DOWN -> throw new IllegalArgumentException("Face-down cards are hidden from bots");
        };
    }
}
