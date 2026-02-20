package com.tamaspinter.backend.mapper;

import com.tamaspinter.backend.entity.CardEntity;
import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.entity.PlayerEntity;
import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.Deck;
import com.tamaspinter.backend.model.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

public class SessionMapper {
    public static GameSessionEntity toEntity(GameSession session) {
        Deck deck = session.getDeck();
        return GameSessionEntity.builder()
                .sessionId(session.getSessionId())
                .started(session.isStarted())
                .finished(session.isFinished())
                .shitheadId(session.getShitheadId())
                .currentPlayerId(session.getPlayers().isEmpty() ? null : session.getCurrentPlayerId())
                .discardPile(cardsToEntities(session.getDiscardPile()))
                .players(statesToEntities(session.getPlayers()))
                .deck(deck != null ? cardsToEntities(new ArrayDeque<>(deck.getCards())) : List.of())
                .config(session.getConfig().toEntity())
                .build();
    }

    public static GameSession fromEntity(GameSessionEntity entity) {
        GameSession session = GameSession.builder()
                .sessionId(entity.getSessionId())
                .build();
        if (entity.isStarted()) {
            session.setStarted(true);
        }
        if (entity.isFinished()) {
            session.setFinished(true);
        }
        session.setShitheadId(entity.getShitheadId());
        Deque<Card> discard = entitiesToCards(entity.getDiscardPile());
        session.getDiscardPile().clear();
        discard.forEach(session.getDiscardPile()::addLast);
        if (entity.getConfig() != null) {
            session.setConfig(GameConfig.fromEntity(entity.getConfig()));
        }
        List<CardEntity> deckEntities = entity.getDeck() != null ? entity.getDeck() : List.of();
        session.setDeck(new Deck(new ArrayList<>(entitiesToCards(deckEntities))));
        session.getPlayers().clear();
        for (PlayerEntity playerEntity : entity.getPlayers()) {
            Player player = Player.builder()
                    .playerId(playerEntity.getPlayerId())
                    .username(playerEntity.getUsername())
                    .build();
            entitiesToCards(playerEntity.getHand()).forEach(player.getHand()::addLast);
            entitiesToCards(playerEntity.getFaceUp()).forEach(player.getFaceUp()::addLast);
            entitiesToCards(playerEntity.getFaceDown()).forEach(player.getFaceDown()::addLast);
            player.setOut(playerEntity.isOut());
            session.getPlayers().add(player);
        }
        String currentPlayerId = entity.getCurrentPlayerId();
        for (int i = 0; i < session.getPlayers().size(); i++) {
            if (session.getPlayers().get(i).getPlayerId().equals(currentPlayerId)) {
                session.setCurrentIndex(i);
                break;
            }
        }
        return session;
    }

    private static List<CardEntity> cardsToEntities(Deque<Card> cards) {
        return cards.stream()
                .map(card -> CardEntity.builder()
                        .suit(card.getSuit())
                        .value(card.getValue())
                        .rule(card.getRule())
                        .alwaysPlayable(card.isAlwaysPlayable())
                        .build())
                .collect(Collectors.toList());
    }

    private static Deque<Card> entitiesToCards(List<CardEntity> entities) {
        Deque<Card> cards = new ArrayDeque<>();
        for (CardEntity entity : entities) {
            cards.addLast(Card.builder()
                    .suit(entity.getSuit())
                    .value(entity.getValue())
                    .rule(entity.getRule())
                    .alwaysPlayable(entity.isAlwaysPlayable())
                    .build());
        }
        return cards;
    }

    private static List<PlayerEntity> statesToEntities(List<Player> states) {
        return states.stream()
                .map(player -> PlayerEntity.builder()
                        .playerId(player.getPlayerId())
                        .username(player.getUsername())
                        .out(player.isOut())
                        .hand(cardsToEntities(player.getHand()))
                        .faceUp(cardsToEntities(player.getFaceUp()))
                        .faceDown(cardsToEntities(player.getFaceDown()))
                        .build())
                .collect(Collectors.toList());
    }

}
