package com.tamaspinter.backend.service;

import com.tamaspinter.backend.model.UserProfile;
import com.tamaspinter.backend.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;

import java.util.Comparator;
import java.util.List;

/**
 * Admin user listing and blocking. Blocking persists the flag first, then performs best-effort
 * cleanup of the user's connections and unstarted lobbies.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    static final String CLAIM_ROW_PREFIX = "__username__#";
    static final String VOICE_ROW_PREFIX = "__voice_";

    private final UserProfileRepository userRepo;
    private final UserConnectionService connections;
    private final LobbyMembershipService lobbies;

    public enum BlockOutcome { UPDATED, SELF_BLOCK_REFUSED, NOT_FOUND }

    public record BlockResult(BlockOutcome outcome, int closedConnections, int removedFromLobbies) {
        static BlockResult of(BlockOutcome outcome) {
            return new BlockResult(outcome, 0, 0);
        }
    }

    /**
     * One row of the admin user list. {@code email} is null until the profile has been written
     * with an email claim.
     */
    public record AdminUserView(String userId, String username, String email, double eloScore, boolean blocked) {
    }

    public List<AdminUserView> listUsers() {
        return userRepo.scanAll().stream()
                .filter(AdminUserService::isUserRow)
                .map(AdminUserService::toView)
                .sorted(Comparator.comparing(AdminUserView::username,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    public BlockResult setBlocked(String adminId, String targetId, boolean blocked) {
        if (blocked && adminId.equals(targetId)) {
            return BlockResult.of(BlockOutcome.SELF_BLOCK_REFUSED);
        }
        if (!userRepo.setBlocked(targetId, blocked)) {
            return BlockResult.of(BlockOutcome.NOT_FOUND);
        }
        if (!blocked) {
            return BlockResult.of(BlockOutcome.UPDATED);
        }
        return new BlockResult(BlockOutcome.UPDATED, disconnectQuietly(targetId), removeFromLobbiesQuietly(targetId));
    }

    private int disconnectQuietly(String userId) {
        try {
            return connections.disconnectUser(userId);
        } catch (SdkException e) {
            log.warn("Could not clean up WebSocket connections for blocked user {}", userId, e);
            return 0;
        }
    }

    private int removeFromLobbiesQuietly(String userId) {
        try {
            return lobbies.removeFromUnstartedLobbies(userId);
        } catch (SdkException e) {
            log.warn("Could not remove blocked user {} from lobbies", userId, e);
            return 0;
        }
    }

    /** Profiles only: skips username claim rows and voice bookkeeping rows (usage, open markers). */
    private static boolean isUserRow(UserProfile profile) {
        String userId = profile.getUserId();
        return userId != null && !userId.startsWith(CLAIM_ROW_PREFIX) && !userId.startsWith(VOICE_ROW_PREFIX);
    }

    private static AdminUserView toView(UserProfile profile) {
        return new AdminUserView(
                profile.getUserId(),
                profile.getUsername(),
                profile.getEmail(),
                profile.getEloScore(),
                Boolean.TRUE.equals(profile.getBlocked()));
    }
}
