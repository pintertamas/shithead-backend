package com.tamaspinter.backend.service;

import com.tamaspinter.backend.entity.GameConfigEntity;
import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.entity.PlayerEntity;
import com.tamaspinter.backend.repository.GameSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Lists games that are visible in the lobby browser: every unfinished game, newest first.
 */
@Service
@RequiredArgsConstructor
public class GameBrowseService {

    static final int MAX_RESULTS = 50;
    static final int MAX_PLAYERS_CAP = 6;
    static final int DECK_CARD_COUNT = 52;
    static final String STATUS_WAITING = "waiting";
    static final String STATUS_IN_PROGRESS = "in_progress";
    static final String UNKNOWN_OWNER = "Unknown";

    private final GameSessionRepository sessionRepo;

    public record OpenGameView(
            String sessionId,
            String ownerName,
            int playerCount,
            int maxPlayers,
            String status,
            int decksCount,
            String createdAt) {
    }

    public List<OpenGameView> listOpenGames() {
        long now = Instant.now().getEpochSecond();
        return sessionRepo.findAll().stream()
                .filter(game -> !game.isFinished())
                .filter(game -> !isExpired(game, now))
                .sorted(Comparator.comparing(GameSessionEntity::getCreatedAt,
                        Comparator.nullsLast(Comparator.<String>reverseOrder())))
                .limit(MAX_RESULTS)
                .map(GameBrowseService::toView)
                .toList();
    }

    /**
     * Seats that fit in one deck-set when every player is dealt their full layout, capped at six.
     */
    static int maxPlayers(GameConfigEntity config) {
        int cardsPerPlayer = config.getFaceDownCount() + config.getFaceUpCount() + config.getHandCount();
        if (cardsPerPlayer <= 0) {
            return MAX_PLAYERS_CAP;
        }
        int bySize = config.getDecksCount() * DECK_CARD_COUNT / cardsPerPlayer;
        return Math.min(MAX_PLAYERS_CAP, bySize);
    }

    /**
     * A game whose TTL has passed is gone or about to be: DynamoDB deletes expired items
     * lazily, sometimes hours late, so it must not be listed as joinable.
     */
    static boolean isExpired(GameSessionEntity game, long nowEpochSeconds) {
        Long ttl = game.getTtl();
        return ttl != null && ttl <= nowEpochSeconds;
    }

    private static OpenGameView toView(GameSessionEntity game) {
        List<PlayerEntity> players = game.getPlayers() == null ? List.of() : game.getPlayers();
        GameConfigEntity config = game.getConfig() == null ? GameConfigEntity.builder().build() : game.getConfig();
        return new OpenGameView(
                game.getSessionId(),
                ownerName(game, players),
                players.size(),
                maxPlayers(config),
                game.isStarted() ? STATUS_IN_PROGRESS : STATUS_WAITING,
                config.getDecksCount(),
                game.getCreatedAt());
    }

    private static String ownerName(GameSessionEntity game, List<PlayerEntity> players) {
        return players.stream()
                .filter(player -> player.getPlayerId() != null && player.getPlayerId().equals(game.getOwnerId()))
                .map(PlayerEntity::getUsername)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(UNKNOWN_OWNER);
    }
}
