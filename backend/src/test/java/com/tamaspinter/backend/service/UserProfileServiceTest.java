package com.tamaspinter.backend.service;

import com.tamaspinter.backend.model.UserProfile;
import com.tamaspinter.backend.repository.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserProfileServiceTest {

    private static final String USER_ID = "user-123456";
    private static final String EMAIL = "user@example.test";
    private static final String OLD_EMAIL = "old@example.test";

    @Mock
    private UserProfileRepository userRepo;

    private UserProfileService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new UserProfileService(userRepo);
        when(userRepo.updateUsernameIfAvailable(any(UserProfile.class), anyString())).thenReturn(true);
    }

    @Test
    void getOrCreateProfile_newUserWithEmailClaim_savesEmailOnCreatedRow() {
        // Given
        when(userRepo.get(USER_ID)).thenReturn(null);

        // When
        UserProfile profile = service.getOrCreateProfile(USER_ID, Map.of("email", EMAIL, "preferred_username", "Ann"));

        // Then
        assertEquals(EMAIL, savedProfile().getEmail());
        assertEquals(EMAIL, profile.getEmail());
    }

    @Test
    void getOrCreateProfile_newUserWithoutEmailClaim_createsRowWithoutEmail() {
        // Given
        when(userRepo.get(USER_ID)).thenReturn(null);

        // When
        service.getOrCreateProfile(USER_ID, Map.of("preferred_username", "Ann"));

        // Then
        assertNull(savedProfile().getEmail());
    }

    @Test
    void getOrCreateProfile_rowWithoutNicknameAndNewEmailClaim_replacesStoredEmail() {
        // Given
        UserProfile stored = UserProfile.builder().userId(USER_ID).eloScore(1200).build();
        stored.setEmail(OLD_EMAIL);
        when(userRepo.get(USER_ID)).thenReturn(stored);

        // When
        service.getOrCreateProfile(USER_ID, Map.of("email", EMAIL));

        // Then
        assertEquals(EMAIL, savedProfile().getEmail());
    }

    @Test
    void getOrCreateProfile_claimWithoutEmail_keepsStoredEmailAndElo() {
        // Given
        UserProfile stored = UserProfile.builder().userId(USER_ID).eloScore(1200).build();
        stored.setEmail(OLD_EMAIL);
        when(userRepo.get(USER_ID)).thenReturn(stored);

        // When
        service.getOrCreateProfile(USER_ID, Map.of("preferred_username", "Ann"));

        // Then
        UserProfile saved = savedProfile();
        assertEquals(OLD_EMAIL, saved.getEmail());
        assertEquals(1200.0, saved.getEloScore(), 0.001);
    }

    @Test
    void getOrCreateProfile_blankEmailClaim_isTreatedAsMissing() {
        // Given
        UserProfile stored = UserProfile.builder().userId(USER_ID).eloScore(1000).build();
        stored.setEmail(OLD_EMAIL);
        when(userRepo.get(USER_ID)).thenReturn(stored);

        // When
        service.getOrCreateProfile(USER_ID, Map.of("email", " "));

        // Then
        assertEquals(OLD_EMAIL, savedProfile().getEmail());
    }

    @Test
    void getOrCreateProfile_withNickname_returnsStoredRowWithoutWriting() {
        // Given
        UserProfile named = UserProfile.builder().userId(USER_ID).username("Ann").eloScore(1000).build();
        when(userRepo.get(USER_ID)).thenReturn(named);

        // When
        UserProfile result = service.getOrCreateProfile(USER_ID, Map.of("email", EMAIL, "preferred_username", "Bob"));

        // Then
        assertSame(named, result);
        assertEquals("Ann", result.getUsername());
        verify(userRepo, never()).save(any(UserProfile.class));
        verify(userRepo, never()).updateUsernameIfAvailable(any(UserProfile.class), anyString());
    }

    @Test
    void getOrCreateProfile_withoutNickname_reservesPreferredUsername() {
        // Given
        when(userRepo.get(USER_ID)).thenReturn(null);

        // When
        service.getOrCreateProfile(USER_ID, Map.of("preferred_username", "Ann", "cognito:username", "ann-login"));

        // Then
        verify(userRepo).updateUsernameIfAvailable(any(UserProfile.class), eq("Ann"));
    }

    @Test
    void getOrCreateProfile_withoutNicknameClaims_reservesPlayerName() {
        // Given
        when(userRepo.get(USER_ID)).thenReturn(null);

        // When
        service.getOrCreateProfile(USER_ID, Map.of());

        // Then
        verify(userRepo).updateUsernameIfAvailable(any(UserProfile.class), eq("Player"));
    }

    @Test
    void getOrCreateProfile_nicknameTaken_retriesWithUserIdSuffix() {
        // Given
        when(userRepo.get(USER_ID)).thenReturn(null);
        when(userRepo.updateUsernameIfAvailable(any(UserProfile.class), eq("Ann"))).thenReturn(false);

        // When
        service.getOrCreateProfile(USER_ID, Map.of("preferred_username", "Ann"));

        // Then
        verify(userRepo).updateUsernameIfAvailable(any(UserProfile.class), eq("Ann-123456"));
    }

    private UserProfile savedProfile() {
        ArgumentCaptor<UserProfile> captor = ArgumentCaptor.forClass(UserProfile.class);
        verify(userRepo).save(captor.capture());
        return captor.getValue();
    }
}
