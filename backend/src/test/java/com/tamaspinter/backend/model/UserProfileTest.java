package com.tamaspinter.backend.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UserProfileTest {

    @Test
    void withoutBlockedFlag_keepsEmail_andDropsBlockFlag() {
        // Given
        UserProfile profile = UserProfile.builder()
                .userId("u1")
                .username("Ann")
                .email("user@example.test")
                .eloScore(1100)
                .build();
        profile.setBlocked(true);

        // When
        UserProfile copy = profile.withoutBlockedFlag();

        // Then: a full-item save must write the email and never the block flag
        assertEquals("user@example.test", copy.getEmail());
        assertNull(copy.getBlocked());
    }
}
