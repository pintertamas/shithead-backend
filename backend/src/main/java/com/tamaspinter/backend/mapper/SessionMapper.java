package com.tamaspinter.backend.mapper;

import com.tamaspinter.backend.bot.BotType;
import com.tamaspinter.backend.entity.CardEntity;
import com.tamaspinter.backend.entity.GameEventEntity;
import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.entity.PlayerEntity;
import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.game.GameEvent;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.Deck;
import com.tamaspinter.backend.model.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

public class SessionMapper {
    public static GameSessionEntity toEntity(GameSession session) {
        Deck deck = session.getDeck();
        return GameSessionEntity.builder()
                .sessionId(session.getSessionId())
                .started(session.isStarted())
                .setupComplete(session.isSetupComplete())
                .finished(session.isFinished())
                .shitheadId(session.getShitheadId())
                .ownerId(session.getOwnerId())
                .currentPlayerId(session.getPlayers().isEmpty() ? null : session.getCurrentPlayerId())
                .discardPile(cardsToEntities(session.getDiscardPile()))
                .players(statesToEntities(session.getPlayers()))
                .deck(deck != null ? cardsToEntities(new ArrayDeque<>(deck.getCards())) : List.of())
                .config(session.getConfig().toEntity())
                .events(eventsToEntities(session.getEvents()))
                .createdAt(session.getCreatedAt())
                .ttl(session.getTtl())
                .build();
    }

    @SuppressWarnings("PMD.NcssCount")
    public static GameSession fromEntity(GameSessionEntity entity) {
        GameSession session = GameSession.builder()
                .sessionId(entity.getSessionId())
                .ownerId(entity.getOwnerId())
                .createdAt(entity.getCreatedAt())
                .ttl(entity.getTtl())
                .build();
        if (entity.isStarted()) {
            session.setStarted(true);
        }
        session.setSetupComplete(entity.isSetupComplete());
        if (entity.isFinished()) {
            session.setFinished(true);
        }
        session.setShitheadId(entity.getShitheadId());
        session.getEvents().addAll(entitiesToEvents(entity.getEvents()));
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
                    .ready(playerEntity.isReady())
                    .botType(BotType.fromName(playerEntity.getBotType()))
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

    /**
     * Copies the Elo bookkeeping of a stored session onto a copy rebuilt from the domain object, because
     * {@link GameSession} does not carry it. Call on every save that rebuilds the entity from a session.
     */
    public static void carryEloState(GameSessionEntity stored, GameSessionEntity rebuilt) {
        rebuilt.setEloUpdated(stored.isEloUpdated());
        rebuilt.setEloChanges(stored.getEloChanges());
    }

    /** Maps persisted events back to domain events; items written before the feed existed have no list. */
    public static List<GameEvent> entitiesToEvents(List<GameEventEntity> entities) {
        if (entities == null) {
            return new ArrayList<>();
        }
        return entities.stream()
                .map(entity -> new GameEvent(
                        entity.getSeq(),
                        entity.getType(),
                        entity.getPlayerId(),
                        entity.getUsername(),
                        entity.getCards() == null ? List.of() : entitiesToCardList(entity.getCards()),
                        entity.getCount(),
                        entity.getTs()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static List<GameEventEntity> eventsToEntities(List<GameEvent> events) {
        return events.stream()
                .map(event -> GameEventEntity.builder()
                        .seq(event.seq())
                        .type(event.type())
                        .playerId(event.playerId())
                        .username(event.username())
                        .cards(cardsToEntities(event.cards()))
                        .count(event.count())
                        .ts(event.ts())
                        .build())
                .collect(Collectors.toList());
    }

    private static List<CardEntity> cardsToEntities(Collection<Card> cards) {
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

    public static List<Card> entitiesToCardList(List<CardEntity> entities) {
        return new ArrayList<>(entitiesToCards(entities));
    }

    private static List<PlayerEntity> statesToEntities(List<Player> states) {
        return states.stream()
                .map(player -> PlayerEntity.builder()
                        .playerId(player.getPlayerId())
                        .username(player.getUsername())
                        .out(player.isOut())
                        .ready(player.isReady())
                        .botType(player.isBot() ? player.getBotType().name() : null)
                        .hand(cardsToEntities(player.getHand()))
                        .faceUp(cardsToEntities(player.getFaceUp()))
                        .faceDown(cardsToEntities(player.getFaceDown()))
                        .build())
                .collect(Collectors.toList());
    }

}
