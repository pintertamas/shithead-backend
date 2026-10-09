package com.tamaspinter.backend.service;

import com.tamaspinter.backend.model.UserProfile;
import com.tamaspinter.backend.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Shared check that rejects requests from users an administrator has blocked.
 */
@Service
@RequiredArgsConstructor
public class BlockedUserGuard {

    public static final String BLOCKED_MESSAGE = "Your account has been blocked.";
    public static final String BLOCKED_BODY = "{\"message\":\"" + BLOCKED_MESSAGE + "\"}";

    private final UserProfileRepository userRepo;

    public boolean isBlocked(String userId) {
        if (userId == null) {
            return false;
        }
        UserProfile profile = userRepo.get(userId);
        return profile != null && Boolean.TRUE.equals(profile.getBlocked());
    }
}
