package com.tamaspinter.backend.service;

import com.tamaspinter.backend.model.UserProfile;
import com.tamaspinter.backend.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BlockedUserGuardTest {

    private final UserProfileRepository userRepo = mock(UserProfileRepository.class);
    private final BlockedUserGuard guard = new BlockedUserGuard(userRepo);

    @Test
    void blockedUser_isRejected() {
        // Given
        UserProfile blocked = UserProfile.builder().userId("u1").build();
        blocked.setBlocked(true);
        when(userRepo.get("u1")).thenReturn(blocked);

        // When / Then
        assertTrue(guard.isBlocked("u1"));
    }

    @Test
    void userWithoutBlockedAttribute_isAllowed() {
        // Given: a missing attribute reads as not blocked
        when(userRepo.get("u2")).thenReturn(UserProfile.builder().userId("u2").build());

        // When / Then
        assertFalse(guard.isBlocked("u2"));
    }

    @Test
    void unknownUser_isAllowed() {
        // Given
        when(userRepo.get("ghost")).thenReturn(null);

        // When / Then
        assertFalse(guard.isBlocked("ghost"));
    }

    @Test
    void missingUserId_isAllowedWithoutLookup() {
        // When / Then
        assertFalse(guard.isBlocked(null));
    }
}
