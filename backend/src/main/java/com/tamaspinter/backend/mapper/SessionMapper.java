package com.tamaspinter.backend.mapper;

import com.tamaspinter.backend.entity.CardEntity;
import com.tamaspinter.backend.entity.GameConfigEntity;
import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.entity.PlayerEntity;
import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.Player;

import com.tamaspinter.backend.model.Deck;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

public class SessionMapper {
    /** Converts a GameSession to its DynamoDB entity representation. */
    public static GameSessionEntity toEntity(GameSession s) {
        GameSessionEntity e = new GameSessionEntity();
        e.setSessionId(s.getSessionId());
        e.setStarted(s.isStarted());
        e.setFinished(s.isFinished());
        e.setShitheadId(s.getShitheadId());
        e.setCurrentPlayerId(s.getPlayers().isEmpty() ? null : s.getCurrentPlayerId());
        e.setDiscardPile(cardsToEntities(s.getDiscardPile()));
        e.setPlayers(statesToEntities(s.getPlayers()));
        Deck deck = s.getDeck();
        e.setDeck(deck != null ? cardsToEntities(new ArrayDeque<>(deck.getCards())) : List.of());
        e.setConfig(s.getConfig().toEntity());
        return e;
    }

    /** Constructs an in-memory GameSession from its DynamoDB entity. */
    public static GameSession fromEntity(GameSessionEntity e) {
        GameSession s = new GameSession(e.getSessionId());
        if (e.isStarted()) s.setStarted(true);
        if (e.isFinished()) s.setFinished(true);
        s.setShitheadId(e.getShitheadId());
        // Rebuild discard pile
        Deque<Card> discard = entitiesToCards(e.getDiscardPile());
        s.getDiscardPile().clear();
        discard.forEach(s.getDiscardPile()::addLast);
        // Restore config
        if (e.getConfig() != null) {
            s.setConfig(GameConfig.fromEntity(e.getConfig()));
        }
        // Rebuild deck
        List<CardEntity> deckEntities = e.getDeck() != null ? e.getDeck() : List.of();
        s.setDeck(new Deck(new ArrayList<>(entitiesToCards(deckEntities))));
        // Rebuild players
        s.getPlayers().clear();
        for (PlayerEntity pe : e.getPlayers()) {
            Player p = new Player(pe.getPlayerId(), pe.getUsername());
            entitiesToCards(pe.getHand()).forEach(p.getHand()::addLast);
            entitiesToCards(pe.getFaceUp()).forEach(p.getFaceUp()::addLast);
            entitiesToCards(pe.getFaceDown()).forEach(p.getFaceDown()::addLast);
            p.setOut(pe.isOut());
            s.getPlayers().add(p);
        }
        // Restore current player index
        String curr = e.getCurrentPlayerId();
        for (int i = 0; i < s.getPlayers().size(); i++) {
            if (s.getPlayers().get(i).getPlayerId().equals(curr)) {
                s.setCurrentIndex(i);
                break;
            }
        }
        return s;
    }

    private static List<CardEntity> cardsToEntities(Deque<Card> cards) {
        return cards.stream()
                .map(c -> {
                    CardEntity ce = new CardEntity();
                    ce.setValue(c.getValue());
                    ce.setRule(c.getRule());
                    ce.setAlwaysPlayable(c.isAlwaysPlayable());
                    return ce;
                })
                .collect(Collectors.toList());
    }

    private static Deque<Card> entitiesToCards(List<CardEntity> entities) {
        Deque<Card> dq = new ArrayDeque<>();
        for (CardEntity ce : entities) {
            dq.addLast(new Card(ce.getSuit(), ce.getValue(), ce.getRule(), ce.isAlwaysPlayable()));
        }
        return dq;
    }

    private static List<PlayerEntity> statesToEntities(List<Player> states) {
        return states.stream()
                .map(ps -> {
                    PlayerEntity pe = new PlayerEntity();
                    pe.setPlayerId(ps.getPlayerId());
                    pe.setUsername(ps.getUsername());
                    pe.setOut(ps.isOut());
                    pe.setHand(cardsToEntities(ps.getHand()));
                    pe.setFaceUp(cardsToEntities(ps.getFaceUp()));
                    pe.setFaceDown(cardsToEntities(ps.getFaceDown()));
                    return pe;
                })
                .collect(Collectors.toList());
    }

    private static List<Player> entitiesToStates(List<PlayerEntity> entities) {
        return entities.stream()
                .map(pe -> {
                    Player p = new Player(pe.getPlayerId(), pe.getUsername());
                    entitiesToCards(pe.getHand()).forEach(p.getHand()::addLast);
                    entitiesToCards(pe.getFaceUp()).forEach(p.getFaceUp()::addLast);
                    entitiesToCards(pe.getFaceDown()).forEach(p.getFaceDown()::addLast);
                    p.setOut(pe.isOut());
                    return p;
                })
                .collect(Collectors.toList());
    }
}
