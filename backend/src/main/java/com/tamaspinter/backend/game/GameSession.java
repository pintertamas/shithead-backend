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
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CyclomaticComplexity"})
public class GameSession {
    private final String sessionId;
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
        Player player = findPlayer(playerId);
        if (!started || setupComplete || player == null || player.isReady()
                || handIndex < 0 || faceUpIndex < 0
                || handIndex >= player.getHand().size() || faceUpIndex >= player.getFaceUp().size()) {
            return false;
        }
        List<Card> hand = new ArrayList<>(player.getHand());
        List<Card> faceUp = new ArrayList<>(player.getFaceUp());
        Card handCard = hand.set(handIndex, faceUp.get(faceUpIndex));
        faceUp.set(faceUpIndex, handCard);
        player.getHand().clear();
        player.getHand().addAll(hand);
        player.getFaceUp().clear();
        player.getFaceUp().addAll(faceUp);
        player.sortHand();
        player.sortFaceUp();
        return true;
    }

    public boolean markReady(String playerId) {
        Player player = findPlayer(playerId);
        if (!started || setupComplete || player == null) {
            return false;
        }
        player.setReady(true);
        setupComplete = players.stream().allMatch(Player::isReady);
        return true;
    }

    private Player findPlayer(String playerId) {
        return players.stream().filter(player -> player.getPlayerId().equals(playerId)).findFirst().orElse(null);
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
        selectedCards.forEach(discardPile::addLast);
        postPlayCleanup(player);
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
        boolean burnedByCount = lastPlayBurned;
        lastPlayBurned = false;
        RuleEngine.playAfterEffect(card, discardPile, player, players);
        boolean burnedByRule = card.getRule() == CardRule.BURNER;
        if ((!burnedByCount && !burnedByRule && !config.canPlayAgain(card.getValue())) || player.isOut()) {
            nextPlayer();
        }
        checkGameEnd();
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
        Player player = players.get(currentIndex);
        discardPile.forEach(player.getHand()::addLast);
        discardPile.clear();
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
        matched.forEach(discardPile::addLast);
        matched.forEach(player.getHand()::remove);
        postPlayCleanup(player);
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
            return PlayResult.INVALID;
        }
        matched.forEach(discardPile::addLast);
        matched.forEach(player.getFaceUp()::remove);
        postPlayCleanup(player);
        return PlayResult.SUCCESS;
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
            matched.forEach(player.getHand()::addLast);
            discardPile.forEach(player.getHand()::addLast);
            discardPile.clear();
            nextPlayer();
            return PlayResult.PICKUP;
        }
        matched.forEach(discardPile::addLast);
        postPlayCleanup(player);
        return PlayResult.SUCCESS;
    }

    private void postPlayCleanup(Player player) {
        lastPlayBurned = RuleEngine.shouldBurn(discardPile, config.getBurnCount());
        if (lastPlayBurned) {
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

