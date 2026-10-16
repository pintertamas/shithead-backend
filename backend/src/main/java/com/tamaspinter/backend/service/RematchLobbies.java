package com.tamaspinter.backend.service;

import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.entity.PlayerEntity;
import com.tamaspinter.backend.bot.BotType;
import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.model.Player;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Builds the unstarted lobby of a rematch from a finished game: same config, the caller as owner and first
 * player, and the original bots as fresh seats.
 */
public final class RematchLobbies {

    /** Lobby lifetime in seconds, the same as the create-game route. */
    public static final long LOBBY_TTL_SECONDS = 3600;
    private static final String CODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 6;
    private static final int MAX_CODE_TRIES = 10;
    private static final DateTimeFormatter CREATED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'+00:00'").withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();

    private RematchLobbies() {
    }

    /**
     * A random 6-character code (A-Z, 0-9) that is not taken. After a few collisions the last candidate is
     * returned anyway, as the create-game route does.
     */
    public static String newCode(Predicate<String> taken) {
        String code = null;
        for (int attempt = 0; attempt < MAX_CODE_TRIES; attempt++) {
            code = randomCode();
            if (!taken.test(code)) {
                return code;
            }
        }
        return code;
    }

    private static String randomCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    /**
     * The new lobby for {@code finished}, owned by the caller, linked back through {@code rematchOf}.
     */
    public static GameSession newLobby(String sessionId, GameSessionEntity finished, String ownerId, String ownerName) {
        Instant now = Instant.now();
        GameSession lobby = GameSession.builder()
                .sessionId(sessionId)
                .ownerId(ownerId)
                .config(finished.getConfig() == null
                        ? GameConfig.defaultGameConfig()
                        : GameConfig.fromEntity(finished.getConfig()))
                .createdAt(CREATED_AT_FORMAT.format(now))
                .ttl(now.getEpochSecond() + LOBBY_TTL_SECONDS)
                .rematchOf(finished.getSessionId())
                .build();
        lobby.getPlayers().add(Player.builder().playerId(ownerId).username(ownerName).build());
        if (finished.getPlayers() != null) {
            finished.getPlayers().stream()
                    .filter(player -> player.getBotType() != null)
                    .map(RematchLobbies::freshBot)
                    .filter(Objects::nonNull)
                    .forEach(lobby.getPlayers()::add);
        }
        return lobby;
    }

    private static Player freshBot(PlayerEntity bot) {
        BotType type = BotType.fromName(bot.getBotType());
        if (type == null) {
            return null;
        }
        return Player.builder()
                .playerId(GameSession.BOT_ID_PREFIX + UUID.randomUUID())
                .username(bot.getUsername())
                .botType(type)
                .build();
    }
}
