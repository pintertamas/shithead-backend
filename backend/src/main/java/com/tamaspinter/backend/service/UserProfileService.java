package com.tamaspinter.backend.service;

import com.tamaspinter.backend.model.UserProfile;
import com.tamaspinter.backend.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserProfileService {
    private final UserProfileRepository userRepo;

    public UserProfile getOrCreateProfile(String userId, Map<String, Object> claims) {
        UserProfile profile = userRepo.get(userId);
        if (hasNickname(profile)) {
            return profile;
        }
        if (profile == null) {
            profile = UserProfile.builder().userId(userId).eloScore(1000).build();
            userRepo.save(profile);
        }
        reserveDefaultNickname(profile, userId, defaultNickname(claims));
        return profile;
    }

    private boolean hasNickname(UserProfile profile) {
        return profile != null && profile.getUsername() != null && !profile.getUsername().isBlank();
    }

    private String defaultNickname(Map<String, Object> claims) {
        Object nickname = claims.get("preferred_username");
        if (nickname == null) {
            nickname = claims.get("cognito:username");
        }
        if (nickname == null) {
            nickname = claims.get("email");
        }
        return nickname instanceof String name ? name : "Player";
    }

    private void reserveDefaultNickname(UserProfile profile, String userId, String nickname) {
        if (userRepo.updateUsernameIfAvailable(profile, nickname)) {
            return;
        }
        String suffix = userId.substring(Math.max(0, userId.length() - 6));
        String uniqueNickname = nickname.substring(0, Math.min(nickname.length(), 17)) + "-" + suffix;
        if (!userRepo.updateUsernameIfAvailable(profile, uniqueNickname)) {
            throw new IllegalStateException("Could not reserve a unique default nickname");
        }
    }
}
