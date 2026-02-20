package com.tamaspinter.backend.game;

import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.mapper.SessionMapper;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.Deck;
import com.tamaspinter.backend.model.Player;
import com.tamaspinter.backend.rules.RuleEngine;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

@Getter
@Setter
@Builder
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

    public void start() {
        int decks = 1 + (players.size() - 1) / 5;
        deck = new Deck(decks, config);
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
        }
        started = true;
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

    public PlayResult playCards(List<Card> cards) {
        if (finished) {
            return PlayResult.INVALID;
        }
        Player player = players.get(currentIndex);
        PlayResult result = resolvePlayResult(player, cards);

        if (result != PlayResult.SUCCESS) {
            return result;
        }
        Card card = cards.get(0);
        RuleEngine.playAfterEffect(card, discardPile, player, players);
        if (!config.canPlayAgain(card.getValue()) || player.isOut()) {
            nextPlayer();
        }
        checkGameEnd();
        return result;
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
        if (finished) {
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
        if (!player.getHand().containsAll(cards)) {
            return PlayResult.INVALID;
        }
        if (notAllCardsAreTheSameValue(cards) || playerCannotPlayAllSelectedCards(cards)) {
            return PlayResult.INVALID;
        }
        cards.forEach(discardPile::addLast);
        player.getHand().removeAll(cards);
        postPlayCleanup(player);
        return PlayResult.SUCCESS;
    }

    private PlayResult playFromFaceUp(List<Card> cards) {
        Player player = players.get(currentIndex);
        if (!player.getHand().isEmpty() || player.getFaceUp().isEmpty()) {
            return PlayResult.INVALID;
        }
        if (!player.getFaceUp().containsAll(cards)) {
            return PlayResult.INVALID;
        }
        if (notAllCardsAreTheSameValue(cards) || playerCannotPlayAllSelectedCards(cards)) {
            return PlayResult.INVALID;
        }
        cards.forEach(discardPile::addLast);
        player.getFaceUp().removeAll(cards);
        postPlayCleanup(player);
        return PlayResult.SUCCESS;
    }

    private PlayResult playFromFaceDown(List<Card> cards) {
        Player player = players.get(currentIndex);
        if (!player.getHand().isEmpty() || !player.getFaceUp().isEmpty() || player.getFaceDown().isEmpty()) {
            return PlayResult.INVALID;
        }
        if (!player.getFaceDown().containsAll(cards)) {
            return PlayResult.INVALID;
        }
        player.getFaceDown().removeAll(cards);
        if (notAllCardsAreTheSameValue(cards) || playerCannotPlayAllSelectedCards(cards)) {
            cards.forEach(player.getHand()::addLast);
            discardPile.forEach(player.getHand()::addLast);
            discardPile.clear();
            nextPlayer();
            return PlayResult.PICKUP;
        }
        cards.forEach(discardPile::addLast);
        postPlayCleanup(player);
        return PlayResult.SUCCESS;
    }

    private void postPlayCleanup(Player player) {
        if (RuleEngine.shouldBurn(discardPile, config.getBurnCount())) {
            discardPile.clear();
        }
        while (player.getHand().size() < config.getHandCount()) {
            Optional<Card> drawn = deck.draw();
            if (drawn.isEmpty()) {
                break;
            }
            player.getHand().addLast(drawn.get());
        }
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


