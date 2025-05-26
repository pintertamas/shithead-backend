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

@Getter
@Setter
public class GameSession {
    private final String sessionId;
    private final List<Player> players = new ArrayList<>();
    private final Deque<Card> discardPile = new ArrayDeque<>();
    private Deck deck;
    private int currentIndex = 0;
    private final GameConfig config = GameConfig.defaultGameConfig();
    private boolean started = false;

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

    // This function will play the cards from the appropriate source (hand, faceUp, faceDown)
    public boolean playCards(List<Card> cards) {
        boolean isSuccessful = false;
        Player p = players.get(currentIndex);
        if (!p.getHand().isEmpty()) isSuccessful = playFromHand(cards);
        else if (!p.getFaceUp().isEmpty()) isSuccessful = playFromFaceUp(cards);
        else if (!p.getFaceDown().isEmpty()) isSuccessful = playFromFaceDown(cards);

        if (isSuccessful) {
            Card c = cards.get(0);
            RuleEngine.playAfterEffect(c, discardPile, p, players);
            if (!config.canPlayAgain(c.getValue())) {
                nextPlayer();
            }
        }
        return isSuccessful;
    }

    private boolean playFromHand(List<Card> cards) {
        Player p = players.get(currentIndex);
        if (p.getHand().isEmpty()) return false;
        if (!p.getHand().containsAll(cards)) return false;
        if (notAllCardsAreTheSameValue(cards) || playerCannotPlayAllSelectedCards(p, cards)) {
            return false;
        }
        cards.forEach(discardPile::addLast);
        p.getHand().removeAll(cards);
        postPlayCleanup(p, cards, config);
        return true;
    }

    private boolean playFromFaceUp(List<Card> cards) {
        Player p = players.get(currentIndex);
        if (!p.getHand().isEmpty() || p.getFaceUp().isEmpty()) return false;
        if (!p.getFaceUp().containsAll(cards)) return false;
        if (notAllCardsAreTheSameValue(cards) || playerCannotPlayAllSelectedCards(p, cards)) {
            return false;
        }
        cards.forEach(discardPile::addLast);
        p.getFaceUp().removeAll(cards);
        postPlayCleanup(p, cards, config);
        return true;
    }

    private boolean playFromFaceDown(List<Card> cards) {
        Player p = players.get(currentIndex);
        if (!p.getHand().isEmpty() || !p.getFaceUp().isEmpty() || p.getFaceDown().isEmpty()) return false;
        if (!p.getFaceDown().containsAll(cards)) return false;
        if (notAllCardsAreTheSameValue(cards) || playerCannotPlayAllSelectedCards(p, cards)) {
            return false;
        }
        cards.forEach(discardPile::addLast);
        p.getFaceDown().removeAll(cards);
        postPlayCleanup(p, cards, config);
        return true;
    }

    private void postPlayCleanup(Player p, List<Card> cards, GameConfig config) {
        // burn check
        if (RuleEngine.shouldBurn(discardPile, config.getBurnCount())) discardPile.clear();
        // refill
        while (p.getHand().size() < config.getHandCount() && deck.draw().isPresent())
            p.getHand().addLast(deck.draw().get());
        // out check
        if (p.getHand().isEmpty() && p.getFaceUp().isEmpty() && p.getFaceDown().isEmpty()) {
            p.setOut(true);
            nextPlayer();
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