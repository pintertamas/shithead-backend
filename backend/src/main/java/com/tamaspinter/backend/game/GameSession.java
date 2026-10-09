package com.tamaspinter.backend.game;

import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.mapper.SessionMapper;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.model.Deck;
import com.tamaspinter.backend.model.Player;
import com.tamaspinter.backend.rules.RuleEngine;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Getter
@Setter
@Builder
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CyclomaticComplexity", "PMD.GodClass"})
public class GameSession {
    /** Activity feed keeps only the most recent entries so the persisted item stays small. */
    public static final int MAX_EVENTS = 30;

    private final String sessionId;
    @Builder.Default
    private final List<GameEvent> events = new ArrayList<>();
    @Builder.Default
    private final List<Player> players = new ArrayList<>();
    @Builder.Default
    private final Deque<Card> discardPile = new ArrayDeque<>();
    private Deck deck;
    private int currentIndex;
    @Builder.Default
    private GameConfig config = GameConfig.defaultGameConfig();
    private boolean started;
    @Builder.Default
    private boolean setupComplete = true;
    private boolean lastPlayBurned;
    private boolean finished;
    private String shitheadId;
    private String ownerId;
    private String createdAt;
    private Long ttl;

    public void addPlayer(String id, String name) {
        if (started) {
            throw new IllegalStateException("Game already started");
        }
        players.add(Player.builder()
                .playerId(id)
                .username(name)
                .build());
    }

    public void removePlayer(String playerId) {
        if (started) {
            throw new IllegalStateException("Cannot leave a started game");
        }
        players.removeIf(p -> p.getPlayerId().equals(playerId));
        if (ownerId != null && ownerId.equals(playerId) && !players.isEmpty()) {
            ownerId = players.get(0).getPlayerId();
        }
    }

    public void start() {
        int cardsPerPlayer = config.getFaceDownCount() + config.getFaceUpCount() + config.getHandCount();
        if (players.size() * cardsPerPlayer > config.getDecksCount() * 52) {
            throw new IllegalStateException("Not enough cards in the selected deck count");
        }
        deck = new Deck(config.getDecksCount(), config);
        for (Player player : players) {
            for (int i = 0; i < config.getFaceDownCount(); i++) {
                player.getFaceDown().add(deck.draw().orElseThrow());
            }
            for (int i = 0; i < config.getFaceUpCount(); i++) {
                player.getFaceUp().add(deck.draw().orElseThrow());
            }
            for (int i = 0; i < config.getHandCount(); i++) {
                player.getHand().add(deck.draw().orElseThrow());
            }
            player.sortHand();
            player.sortFaceUp();
            player.setReady(false);
        }
        started = true;
        setupComplete = false;
    }

    public boolean swapStartingCards(String playerId, int handIndex, int faceUpIndex) {
        return swapStartingCards(playerId, List.of(handIndex), List.of(faceUpIndex));
    }

    /**
     * Swaps the i-th selected hand card with the i-th selected face-up card, during setup only.
     * Nothing changes unless both lists are non-empty, of equal size, free of duplicates and in range.
     */
    public boolean swapStartingCards(String playerId, List<Integer> handIndices, List<Integer> faceUpIndices) {
        Player player = findPlayer(playerId);
        if (!started || setupComplete || player == null || player.isReady()
                || handIndices == null || faceUpIndices == null
                || handIndices.isEmpty() || handIndices.size() != faceUpIndices.size()
                || !isValidSelection(handIndices, player.getHand().size())
                || !isValidSelection(faceUpIndices, player.getFaceUp().size())) {
            return false;
        }
        List<Card> hand = new ArrayList<>(player.getHand());
        List<Card> faceUp = new ArrayList<>(player.getFaceUp());
        for (int i = 0; i < handIndices.size(); i++) {
            int handIndex = handIndices.get(i);
            int faceUpIndex = faceUpIndices.get(i);
            Card handCard = hand.set(handIndex, faceUp.get(faceUpIndex));
            faceUp.set(faceUpIndex, handCard);
        }
        player.getHand().clear();
        player.getHand().addAll(hand);
        player.getFaceUp().clear();
        player.getFaceUp().addAll(faceUp);
        player.sortHand();
        player.sortFaceUp();
        return true;
    }

    private static boolean isValidSelection(List<Integer> indices, int size) {
        Set<Integer> seen = new HashSet<>();
        for (Integer index : indices) {
            if (index == null || index < 0 || index >= size || !seen.add(index)) {
                return false;
            }
        }
        return true;
    }

    public boolean markReady(String playerId) {
        Player player = findPlayer(playerId);
        if (!started || setupComplete || player == null) {
            return false;
        }
        player.setReady(true);
        recordEvent(GameEventType.READY, player, List.of(), 0);
        setupComplete = players.stream().allMatch(Player::isReady);
        return true;
    }

    private Player findPlayer(String playerId) {
        return players.stream().filter(player -> player.getPlayerId().equals(playerId)).findFirst().orElse(null);
    }

    /** Appends a feed entry, dropping the oldest once the cap is reached. Sequence numbers only ever grow. */
    private void recordEvent(GameEventType type, Player player, List<Card> cards, int count) {
        long seq = events.isEmpty() ? 1L : events.get(events.size() - 1).seq() + 1;
        events.add(new GameEvent(seq, type, player.getPlayerId(), player.getUsername(),
                List.copyOf(cards), count, System.currentTimeMillis()));
        while (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
    }

    /** Places the played cards on the pile, logs the play, then applies burn and out checks. */
    private void commitPlay(Player player, List<Card> played) {
        played.forEach(discardPile::addLast);
        recordEvent(GameEventType.PLAYED, player, played, played.size());
        postPlayCleanup(player);
    }

    private boolean notAllCardsAreTheSameValue(List<Card> cards) {
        if (cards.isEmpty()) {
            return true;
        }
        int value = cards.get(0).getValue();
        for (Card card : cards) {
            if (card.getValue() != value) {
                return true;
            }
        }
        return false;
    }

    private boolean playerCannotPlayAllSelectedCards(List<Card> cards) {
        for (Card card : cards) {
            if (!RuleEngine.canPlay(card, discardPile)) {
                return true;
            }
        }
        return false;
    }

    private Optional<List<Card>> matchSelectedCards(List<Card> available, List<Card> selected) {
        if (selected == null || selected.isEmpty()) {
            return Optional.empty();
        }
        List<Card> unmatched = new ArrayList<>(available);
        List<Card> matched = new ArrayList<>();
        for (Card selectedCard : selected) {
            int match = -1;
            for (int i = 0; i < unmatched.size(); i++) {
                if (sameCard(unmatched.get(i), selectedCard)) {
                    match = i;
                    break;
                }
            }
            if (match < 0) {
                return Optional.empty();
            }
            matched.add(unmatched.remove(match));
        }
        return Optional.of(matched);
    }

    private boolean sameCard(Card left, Card right) {
        return left != null && right != null
                && left.getSuit() == right.getSuit()
                && left.getValue() == right.getValue()
                && left.getRule() == right.getRule()
                && left.isAlwaysPlayable() == right.isAlwaysPlayable();
    }

    public PlayResult playCards(List<Card> cards) {
        if (finished || !setupComplete) {
            return PlayResult.INVALID;
        }
        Player player = players.get(currentIndex);
        PlayResult result = resolvePlayResult(player, cards);

        if (result != PlayResult.SUCCESS) {
            return result;
        }
        finishSuccessfulPlay(cards.get(0), player);
        return result;
    }

    public PlayResult playSelections(List<CardSelection> selections) {
        if (finished || !setupComplete || selections == null || selections.isEmpty()) {
            return PlayResult.INVALID;
        }
        Player player = players.get(currentIndex);
        ResolvedSelections resolved = resolveSelections(player, selections);
        if (resolved == null) {
            return PlayResult.INVALID;
        }
        PlayResult result;
        if (resolved.sources().contains(CardSource.FACE_DOWN)) {
            result = selections.size() == 1 && resolved.sources().size() == 1
                    ? playFromFaceDown(resolved.cards())
                    : PlayResult.INVALID;
        } else if (isMixedHandAndFaceUp(resolved.sources())) {
            result = playMixedHandAndFaceUp(player, selections, resolved.cards());
        } else {
            result = resolved.sources().contains(CardSource.HAND)
                    ? playFromHand(resolved.cards())
                    : playFromFaceUp(resolved.cards());
        }
        if (result == PlayResult.SUCCESS) {
            finishSuccessfulPlay(resolved.cards().get(0), player);
        }
        return result;
    }

    private ResolvedSelections resolveSelections(Player player, List<CardSelection> selections) {
        List<Card> hand = new ArrayList<>(player.getHand());
        List<Card> faceUp = new ArrayList<>(player.getFaceUp());
        List<Card> faceDown = new ArrayList<>(player.getFaceDown());
        List<Card> selectedCards = new ArrayList<>();
        Set<CardSelection> seen = new HashSet<>();
        Set<CardSource> sources = EnumSet.noneOf(CardSource.class);
        for (CardSelection selection : selections) {
            if (selection == null || selection.source() == null || selection.index() < 0
                    || !seen.add(selection)) {
                return null;
            }
            sources.add(selection.source());
            List<Card> available = cardsForSource(selection.source(), hand, faceUp, faceDown);
            if (selection.index() >= available.size()) {
                return null;
            }
            selectedCards.add(available.get(selection.index()));
        }
        return new ResolvedSelections(sources, selectedCards);
    }

    private boolean isMixedHandAndFaceUp(Set<CardSource> sources) {
        return sources.contains(CardSource.HAND) && sources.contains(CardSource.FACE_UP);
    }

    private record ResolvedSelections(Set<CardSource> sources, List<Card> cards) { }

    private List<Card> cardsForSource(
            CardSource source, List<Card> hand, List<Card> faceUp, List<Card> faceDown) {
        return switch (source) {
            case HAND -> hand;
            case FACE_UP -> faceUp;
            case FACE_DOWN -> faceDown;
        };
    }

    private PlayResult playMixedHandAndFaceUp(
            Player player, List<CardSelection> selections, List<Card> selectedCards) {
        if (!isValidMixedPlay(selectedCards)) {
            return PlayResult.INVALID;
        }
        removeMixedSelections(player, selections, selectedCards);
        commitPlay(player, selectedCards);
        return PlayResult.SUCCESS;
    }

    private boolean isValidMixedPlay(List<Card> selectedCards) {
        return config.isAllowMixedHandAndFaceUpWhenDeckEmpty()
                && deck != null && deck.getCards().isEmpty()
                && !notAllCardsAreTheSameValue(selectedCards)
                && !playerCannotPlayAllSelectedCards(selectedCards);
    }

    private void removeMixedSelections(Player player, List<CardSelection> selections, List<Card> selectedCards) {
        for (int i = 0; i < selections.size(); i++) {
            CardSelection selection = selections.get(i);
            if (selection.source() == CardSource.HAND) {
                player.getHand().remove(selectedCards.get(i));
            } else {
                player.getFaceUp().remove(selectedCards.get(i));
            }
        }
    }

    private void finishSuccessfulPlay(Card card, Player player) {
        boolean playsAgain = grantsAnotherTurn(card, player);
        applyAfterEffect(card, player);
        if (playsAgain) {
            recordEvent(GameEventType.PLAYED_AGAIN, player, List.of(), 0);
        } else {
            nextPlayer();
        }
        checkGameEnd();
    }

    /** Also consumes the pile-burn flag set by postPlayCleanup, so it must run exactly once per successful play. */
    private boolean grantsAnotherTurn(Card card, Player player) {
        boolean burnedByCount = lastPlayBurned;
        lastPlayBurned = false;
        boolean burnedByRule = card.getRule() == CardRule.BURNER;
        return (burnedByCount || burnedByRule || config.canPlayAgain(card.getValue())) && !player.isOut();
    }

    private void applyAfterEffect(Card card, Player player) {
        int pileBeforeRule = discardPile.size();
        RuleEngine.playAfterEffect(card, discardPile, player, players);
        if (card.getRule() == CardRule.BURNER && pileBeforeRule > 0) {
            recordEvent(GameEventType.BURNED, player, List.of(), pileBeforeRule);
        }
        if (card.getRule() == CardRule.REVERSE) {
            recordEvent(GameEventType.REVERSED, player, List.of(), 0);
        }
    }

    private PlayResult resolvePlayResult(Player player, List<Card> cards) {
        if (!player.getHand().isEmpty()) {
            return playFromHand(cards);
        }
        if (!player.getFaceUp().isEmpty()) {
            return playFromFaceUp(cards);
        }
        if (!player.getFaceDown().isEmpty()) {
            return playFromFaceDown(cards);
        }
        return PlayResult.INVALID;
    }

    public PlayResult pickupPile() {
        if (finished || !setupComplete || discardPile.isEmpty()) {
            return PlayResult.INVALID;
        }
        return pickUpPile(players.get(currentIndex), List.of(), GameEventType.PICKED_UP);
    }

    /**
     * Moves the pile and any failed cards into the player's hand, logs the pickup, and passes the turn.
     * The failed cards are the ones revealed by a blind flip or an illegal face-up play.
     */
    private PlayResult pickUpPile(Player player, List<Card> failedCards, GameEventType type) {
        int count = discardPile.size() + failedCards.size();
        failedCards.forEach(player.getHand()::addLast);
        discardPile.forEach(player.getHand()::addLast);
        recordEvent(type, player, failedCards, count);
        discardPile.clear();
        player.sortHand();
        nextPlayer();
        return PlayResult.PICKUP;
    }

    private PlayResult playFromHand(List<Card> cards) {
        Player player = players.get(currentIndex);
        if (player.getHand().isEmpty()) {
            return PlayResult.INVALID;
        }
        Optional<List<Card>> matchedCards = matchSelectedCards(new ArrayList<>(player.getHand()), cards);
        if (matchedCards.isEmpty()) {
            return PlayResult.INVALID;
        }
        List<Card> matched = matchedCards.get();
        if (notAllCardsAreTheSameValue(matched) || playerCannotPlayAllSelectedCards(matched)) {
            return PlayResult.INVALID;
        }
        matched.forEach(player.getHand()::remove);
        commitPlay(player, matched);
        return PlayResult.SUCCESS;
    }

    private PlayResult playFromFaceUp(List<Card> cards) {
        Player player = players.get(currentIndex);
        if (!player.getHand().isEmpty() || player.getFaceUp().isEmpty()) {
            return PlayResult.INVALID;
        }
        Optional<List<Card>> matchedCards = matchSelectedCards(new ArrayList<>(player.getFaceUp()), cards);
        if (matchedCards.isEmpty()) {
            return PlayResult.INVALID;
        }
        List<Card> matched = matchedCards.get();
        if (notAllCardsAreTheSameValue(matched) || playerCannotPlayAllSelectedCards(matched)) {
            return config.isAllowFailedFaceUpPlay()
                    ? pickUpAfterFailedFaceUpPlay(player, matched)
                    : PlayResult.INVALID;
        }
        matched.forEach(player.getFaceUp()::remove);
        commitPlay(player, matched);
        return PlayResult.SUCCESS;
    }

    /**
     * Rule for an illegal face-up play with an empty hand: the selected cards are placed on the pile and the
     * player immediately picks up the whole pile, including those cards. Mirrors a failed blind flip.
     */
    private PlayResult pickUpAfterFailedFaceUpPlay(Player player, List<Card> matched) {
        matched.forEach(player.getFaceUp()::remove);
        return pickUpPile(player, matched, GameEventType.FAILED_PLAY);
    }

    private PlayResult playFromFaceDown(List<Card> cards) {
        Player player = players.get(currentIndex);
        if (!player.getHand().isEmpty() || !player.getFaceUp().isEmpty() || player.getFaceDown().isEmpty()) {
            return PlayResult.INVALID;
        }
        Optional<List<Card>> matchedCards = matchSelectedCards(new ArrayList<>(player.getFaceDown()), cards);
        if (matchedCards.isEmpty()) {
            return PlayResult.INVALID;
        }
        List<Card> matched = matchedCards.get();
        matched.forEach(player.getFaceDown()::remove);
        if (notAllCardsAreTheSameValue(matched) || playerCannotPlayAllSelectedCards(matched)) {
            return pickUpPile(player, matched, GameEventType.FAILED_FLIP);
        }
        commitPlay(player, matched);
        return PlayResult.SUCCESS;
    }

    private void postPlayCleanup(Player player) {
        lastPlayBurned = RuleEngine.shouldBurn(discardPile, config.getBurnCount());
        if (lastPlayBurned) {
            recordEvent(GameEventType.BURNED, player, List.of(), discardPile.size());
            discardPile.clear();
        }
        while (player.getHand().size() < config.getHandCount()) {
            Optional<Card> drawn = deck.draw();
            if (drawn.isEmpty()) {
                break;
            }
            player.getHand().addLast(drawn.get());
        }
        player.sortHand();
        if (player.getHand().isEmpty() && player.getFaceUp().isEmpty() && player.getFaceDown().isEmpty()) {
            player.setOut(true);
            recordEvent(GameEventType.OUT, player, List.of(), 0);
        }
    }

    private void checkGameEnd() {
        long activePlayers = players.stream().filter(pl -> !pl.isOut()).count();
        if (activePlayers <= 1) {
            finished = true;
            Optional<Player> remainingPlayer = players.stream()
                    .filter(pl -> !pl.isOut())
                    .findFirst();
            if (remainingPlayer.isPresent()) {
                Player remaining = remainingPlayer.get();
                shitheadId = remaining.getPlayerId();
                recordEvent(GameEventType.FINISHED, remaining, List.of(), 0);
            }
        }
    }

    private void nextPlayer() {
        do {
            currentIndex = (currentIndex + 1) % players.size();
        }
        while (players.get(currentIndex).isOut());
    }

    public String getCurrentPlayerId() {
        if (players.isEmpty() || currentIndex < 0 || currentIndex >= players.size()) {
            return null;
        }
        return players.get(currentIndex).getPlayerId();
    }

    public GameSessionEntity toEntity() {
        return SessionMapper.toEntity(this);
    }

    @Override
    public String toString() {
        return "GameSession{"
                + "sessionId='" + sessionId + '\''
                + ", number of players=" + players.size()
                + ", discardPile=" + discardPile
                + ", deck=" + deck
                + ", currentIndex=" + currentIndex
                + ", config=" + config
                + ", started=" + started
                + '}';
    }
}

