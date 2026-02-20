package com.tamaspinter.backend.game;

import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.mapper.SessionMapper;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.Deck;
import com.tamaspinter.backend.model.Player;
import com.tamaspinter.backend.rules.RuleEngine;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

@Getter
@Setter
public class GameSession {
    private final String sessionId;
    private final List<Player> players = new ArrayList<>();
    private final Deque<Card> discardPile = new ArrayDeque<>();
    private Deck deck;
    private int currentIndex = 0;
    private GameConfig config = GameConfig.defaultGameConfig();
    private boolean started = false;
    private boolean finished = false;
    private String shitheadId = null;

    public GameSession(String sessionId) {
        this.sessionId = sessionId;
    }

    public void addPlayer(String id, String name) {
        if (started) throw new IllegalStateException("Game already started");
        players.add(new Player(id, name));
    }

    public void start() {
        int decks = 1 + (players.size() - 1) / 5; // make sure there are enough cards for all players
        deck = new Deck(decks, config);
        // deal faceDown, faceUp, hand to each player
        for (Player p : players) {
            for (int i = 0; i < config.getFaceDownCount(); i++)
                p.getFaceDown().add(deck.draw().orElseThrow());
            for (int i = 0; i < config.getFaceUpCount(); i++)
                p.getFaceUp().add(deck.draw().orElseThrow());
            for (int i = 0; i < config.getHandCount(); i++)
                p.getHand().add(deck.draw().orElseThrow());
        }
        started = true;
    }

    private boolean notAllCardsAreTheSameValue(List<Card> cards) {
        if (cards.isEmpty()) return true;
        int value = cards.get(0).getValue();
        for (Card c : cards) {
            if (c.getValue() != value) return true;
        }
        return false;
    }

    private boolean playerCannotPlayAllSelectedCards(Player p, List<Card> cards) {
        for (Card card : cards)
            if (!RuleEngine.canPlay(card, discardPile)) {
                return true;
            }
        return false;
    }

    // Plays cards from the appropriate source (hand -> faceUp -> faceDown).
    // Returns SUCCESS, PICKUP (auto-pickup on blind face-down fail), or INVALID.
    public PlayResult playCards(List<Card> cards) {
        if (finished) return PlayResult.INVALID;
        Player p = players.get(currentIndex);
        PlayResult result;
        if (!p.getHand().isEmpty()) result = playFromHand(cards);
        else if (!p.getFaceUp().isEmpty()) result = playFromFaceUp(cards);
        else if (!p.getFaceDown().isEmpty()) result = playFromFaceDown(cards);
        else return PlayResult.INVALID;

        if (result == PlayResult.SUCCESS) {
            Card c = cards.get(0);
            RuleEngine.playAfterEffect(c, discardPile, p, players);
            // Advance if: card doesn't grant replay, OR the player just finished all their cards
            if (!config.canPlayAgain(c.getValue()) || p.isOut()) {
                nextPlayer();
            }
            checkGameEnd();
        }
        return result;
    }

    // Player explicitly picks up the discard pile when they cannot play from hand/faceUp.
    public PlayResult pickupPile() {
        if (finished) return PlayResult.INVALID;
        Player p = players.get(currentIndex);
        discardPile.forEach(p.getHand()::addLast);
        discardPile.clear();
        nextPlayer();
        return PlayResult.PICKUP;
    }

    private PlayResult playFromHand(List<Card> cards) {
        Player p = players.get(currentIndex);
        if (p.getHand().isEmpty()) return PlayResult.INVALID;
        if (!p.getHand().containsAll(cards)) return PlayResult.INVALID;
        if (notAllCardsAreTheSameValue(cards) || playerCannotPlayAllSelectedCards(p, cards)) {
            return PlayResult.INVALID;
        }
        cards.forEach(discardPile::addLast);
        p.getHand().removeAll(cards);
        postPlayCleanup(p);
        return PlayResult.SUCCESS;
    }

    private PlayResult playFromFaceUp(List<Card> cards) {
        Player p = players.get(currentIndex);
        if (!p.getHand().isEmpty() || p.getFaceUp().isEmpty()) return PlayResult.INVALID;
        if (!p.getFaceUp().containsAll(cards)) return PlayResult.INVALID;
        if (notAllCardsAreTheSameValue(cards) || playerCannotPlayAllSelectedCards(p, cards)) {
            return PlayResult.INVALID;
        }
        cards.forEach(discardPile::addLast);
        p.getFaceUp().removeAll(cards);
        postPlayCleanup(p);
        return PlayResult.SUCCESS;
    }

    private PlayResult playFromFaceDown(List<Card> cards) {
        Player p = players.get(currentIndex);
        if (!p.getHand().isEmpty() || !p.getFaceUp().isEmpty() || p.getFaceDown().isEmpty()) return PlayResult.INVALID;
        if (!p.getFaceDown().containsAll(cards)) return PlayResult.INVALID;
        // Remove the flipped card(s) from faceDown regardless of outcome
        p.getFaceDown().removeAll(cards);
        if (notAllCardsAreTheSameValue(cards) || playerCannotPlayAllSelectedCards(p, cards)) {
            // Blind flip failed: player picks up the pile plus the flipped cards
            cards.forEach(p.getHand()::addLast);
            discardPile.forEach(p.getHand()::addLast);
            discardPile.clear();
            nextPlayer();
            return PlayResult.PICKUP;
        }
        cards.forEach(discardPile::addLast);
        postPlayCleanup(p);
        return PlayResult.SUCCESS;
    }

    private void postPlayCleanup(Player p) {
        // burn check
        if (RuleEngine.shouldBurn(discardPile, config.getBurnCount())) discardPile.clear();
        // refill hand from deck
        Optional<Card> drawn;
        while (p.getHand().size() < config.getHandCount() && (drawn = deck.draw()).isPresent())
            p.getHand().addLast(drawn.get());
        // out check — nextPlayer() is handled by the caller (playCards)
        if (p.getHand().isEmpty() && p.getFaceUp().isEmpty() && p.getFaceDown().isEmpty()) {
            p.setOut(true);
        }
    }

    private void checkGameEnd() {
        long activePlayers = players.stream().filter(pl -> !pl.isOut()).count();
        if (activePlayers <= 1) {
            finished = true;
            players.stream()
                    .filter(pl -> !pl.isOut())
                    .findFirst()
                    .ifPresent(pl -> shitheadId = pl.getPlayerId());
        }
    }

    private void nextPlayer() {
        do {
            currentIndex = (currentIndex + 1) % players.size();
        }
        while (players.get(currentIndex).isOut());
    }

    public String getCurrentPlayerId() {
        if (players.isEmpty() || currentIndex < 0 || currentIndex >= players.size()) return null;
        return players.get(currentIndex).getPlayerId();
    }

    public GameSessionEntity toEntity() {
        return SessionMapper.toEntity(this);
    }

    @Override
    public String toString() {
        return "GameSession{" +
                "sessionId='" + sessionId + '\'' +
                ", number of players=" + players.size() +
                ", discardPile=" + discardPile +
                ", deck=" + deck +
                ", currentIndex=" + currentIndex +
                ", config=" + config +
                ", started=" + started +
                '}';
    }
}