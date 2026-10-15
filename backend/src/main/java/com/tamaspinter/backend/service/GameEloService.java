package com.tamaspinter.backend.service;

import com.tamaspinter.backend.bot.BotType;
import com.tamaspinter.backend.entity.EloChangeEntity;
import com.tamaspinter.backend.entity.PlayerEntity;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.model.Player;
import com.tamaspinter.backend.model.UserProfile;
import com.tamaspinter.backend.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Persists the Elo update of a finished game, bots included. Ratings of bots belong to the bot TYPE (one users-table
 * row per type, see {@link EloService#botTypeId}), never to a seat; the maths is {@link EloService#rateGame}.
 */
@Slf4j
@RequiredArgsConstructor
public class GameEloService {

    /** Tries to apply a bot type rating update before giving up on concurrent changes. */
    private static final int BOT_RATING_ATTEMPTS = 3;

    private final UserProfileRepository userRepo;

    /**
     * Applies the update for a finished game and returns each seat's before/after rating; a bot seat carries its
     * type's change. Returns an empty map when nothing was updated (no human, fewer than two rated participants,
     * concurrent rating changes, or a DynamoDB failure); the caller then leaves {@code eloUpdated} unset so the
     * update can be retried. Games ended by {@code finishStalledBotGame} are rated like any other: only bots were
     * left, so the shithead is a bot and its type loses while the humans, who finished earlier, win.
     */
    public Map<String, EloChangeEntity> update(GameSession session) {
        List<Player> players = session.getPlayers();
        if (players.stream().allMatch(Player::isBot)) {
            log.info("Skipping Elo update for session {} because no human played", session.getSessionId());
            return Map.of();
        }
        try {
            for (int attempt = 0; attempt < BOT_RATING_ATTEMPTS; attempt++) {
                Optional<Map<String, EloService.EloChange>> applied = tryUpdate(session);
                if (applied.isPresent()) {
                    return toSeats(players, applied.get());
                }
            }
            log.warn("Elo update for session {} gave up after concurrent bot rating changes", session.getSessionId());
        } catch (SdkException e) {
            log.error("Elo update failed for session {}", session.getSessionId(), e);
        }
        return Map.of();
    }

    /**
     * One attempt. Empty means another game changed a bot type rating meanwhile (nothing was written for humans);
     * an empty map inside means there was nothing to rate.
     */
    private Optional<Map<String, EloService.EloChange>> tryUpdate(GameSession session) {
        List<Player> players = session.getPlayers();
        List<String> humanIds = players.stream().filter(player -> !player.isBot()).map(Player::getPlayerId).toList();
        List<BotType> types = players.stream().filter(Player::isBot).map(Player::getBotType).distinct().toList();
        Map<String, Double> ratings = currentRatings(humanIds, types);
        Map<String, EloService.EloChange> changes = EloService.rateGame(players, session.getShitheadId(), ratings);
        if (changes.isEmpty()) {
            log.warn("Skipping Elo update for session {}: nothing to rate", session.getSessionId());
            return Optional.of(changes);
        }
        // Conditional bot type writes first: on a race recompute before any human is written.
        for (BotType type : types) {
            EloService.EloChange change = changes.get(EloService.botTypeId(type));
            if (!userRepo.updateBotTypeRating(type, change.before(), change.after())) {
                return Optional.empty();
            }
        }
        humanIds.forEach(id -> saveHuman(id, changes.get(id)));
        return Optional.of(changes);
    }

    private Map<String, Double> currentRatings(List<String> humanIds, List<BotType> types) {
        Map<String, Double> ratings = new HashMap<>();
        userRepo.batchGet(humanIds).forEach(profile -> ratings.put(profile.getUserId(), profile.getEloScore()));
        types.forEach(type -> ratings.put(EloService.botTypeId(type), userRepo.getOrCreateBotType(type).getEloScore()));
        return ratings;
    }

    private void saveHuman(String id, EloService.EloChange change) {
        UserProfile profile = change == null ? null : userRepo.get(id);
        if (profile != null) {
            profile.setEloScore(change.after());
            userRepo.save(profile);
        }
    }

    private static Map<String, EloChangeEntity> toSeats(List<Player> players,
            Map<String, EloService.EloChange> changes) {
        Map<String, EloChangeEntity> recorded = new HashMap<>();
        players.forEach(player -> {
            EloService.EloChange change = changes.get(EloService.participantId(player));
            if (change != null) {
                recorded.put(player.getPlayerId(),
                        EloChangeEntity.builder().before(change.before()).after(change.after()).build());
            }
        });
        return recorded;
    }

    /**
     * Profiles for a session leaderboard keyed by seat: humans by their profile, a bot seat as a bot row under its
     * seat name carrying its TYPE's current rating (the start rating while the type has no row yet). Humans without
     * a profile are absent.
     */
    public Map<String, UserProfile> sessionProfiles(List<PlayerEntity> seats) {
        List<String> humanIds = seats.stream().filter(seat -> seat.getBotType() == null)
                .map(PlayerEntity::getPlayerId).toList();
        List<BotType> types = seats.stream().map(seat -> BotType.fromName(seat.getBotType()))
                .filter(Objects::nonNull).distinct().toList();
        List<String> lookupIds = new java.util.ArrayList<>(humanIds);
        types.forEach(type -> lookupIds.add(EloService.botTypeId(type)));
        Map<String, UserProfile> profiles = new HashMap<>();
        userRepo.batchGet(lookupIds).forEach(profile -> profiles.put(profile.getUserId(), profile));
        seats.stream().filter(seat -> BotType.fromName(seat.getBotType()) != null).forEach(seat -> {
            UserProfile typeRow = profiles.get(EloService.botTypeId(BotType.fromName(seat.getBotType())));
            profiles.put(seat.getPlayerId(), UserProfile.builder()
                    .userId(seat.getPlayerId())
                    .username(seat.getUsername())
                    .eloScore(typeRow == null ? GameSession.DEFAULT_RATING : typeRow.getEloScore())
                    .bot(true)
                    .build());
        });
        return profiles;
    }
}
