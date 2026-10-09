package com.tamaspinter.backend.service;

import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.entity.PlayerEntity;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.mapper.SessionMapper;
import com.tamaspinter.backend.repository.GameSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Removes a player from unstarted lobbies using the same steps as the leaveGame route.
 */
@Service
@RequiredArgsConstructor
public class LobbyMembershipService {

    private final GameSessionRepository sessionRepo;

    /**
     * Removes the user from every unstarted lobby they are seated in.
     *
     * @return number of lobbies the user was removed from
     */
    public int removeFromUnstartedLobbies(String userId) {
        int removed = 0;
        for (GameSessionEntity game : sessionRepo.findAll()) {
            if (game.isStarted() || !hasPlayer(game, userId)) {
                continue;
            }
            GameSession session = SessionMapper.fromEntity(game);
            session.removePlayer(userId);
            if (session.getPlayers().isEmpty()) {
                sessionRepo.delete(game.getSessionId());
            } else {
                sessionRepo.save(session.toEntity());
            }
            removed++;
        }
        return removed;
    }

    private static boolean hasPlayer(GameSessionEntity game, String userId) {
        List<PlayerEntity> players = game.getPlayers();
        return players != null && players.stream().anyMatch(player -> userId.equals(player.getPlayerId()));
    }
}
