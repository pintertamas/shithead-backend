package com.tamaspinter.backend.service;

import com.tamaspinter.backend.model.UserProfile;
import com.tamaspinter.backend.repository.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.core.exception.SdkException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class AdminUserServiceTest {

    private static final String ADMIN = "admin-1";
    private static final String TARGET = "player-7";

    @Mock
    private UserProfileRepository userRepo;
    @Mock
    private UserConnectionService connections;
    @Mock
    private LobbyMembershipService lobbies;

    private AdminUserService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new AdminUserService(userRepo, connections, lobbies);
    }

    @Test
    void listUsers_excludesClaimRows_andSortsByUsername() {
        // Given
        UserProfile zed = profile("u-zed", "zed", 1200, null);
        UserProfile alice = profile("u-alice", "Alice", 1000, true);
        UserProfile claim = profile("__username__#alice", null, 0, null);
        when(userRepo.scanAll()).thenReturn(List.of(zed, claim, alice));

        // When
        List<AdminUserService.AdminUserView> users = service.listUsers();

        // Then
        assertEquals(2, users.size());
        assertEquals("u-alice", users.get(0).userId());
        assertTrue(users.get(0).blocked());
        assertEquals("u-zed", users.get(1).userId());
        assertEquals(false, users.get(1).blocked());
    }

    @Test
    void block_setsFlag_andCleansConnectionsAndLobbies() {
        // Given
        when(userRepo.setBlocked(TARGET, true)).thenReturn(true);
        when(connections.disconnectUser(TARGET)).thenReturn(2);
        when(lobbies.removeFromUnstartedLobbies(TARGET)).thenReturn(1);

        // When
        AdminUserService.BlockResult result = service.setBlocked(ADMIN, TARGET, true);

        // Then
        assertEquals(AdminUserService.BlockOutcome.UPDATED, result.outcome());
        assertEquals(2, result.closedConnections());
        assertEquals(1, result.removedFromLobbies());
    }

    @Test
    void unblock_clearsFlag_withoutCleanup() {
        // Given
        when(userRepo.setBlocked(TARGET, false)).thenReturn(true);

        // When
        AdminUserService.BlockResult result = service.setBlocked(ADMIN, TARGET, false);

        // Then
        assertEquals(AdminUserService.BlockOutcome.UPDATED, result.outcome());
        verify(userRepo).setBlocked(TARGET, false);
        verifyNoInteractions(connections, lobbies);
    }

    @Test
    void block_ofOwnAccount_isRefused_withoutTouchingStorage() {
        // Given / When
        AdminUserService.BlockResult result = service.setBlocked(ADMIN, ADMIN, true);

        // Then
        assertEquals(AdminUserService.BlockOutcome.SELF_BLOCK_REFUSED, result.outcome());
        verify(userRepo, never()).setBlocked(anyString(), org.mockito.ArgumentMatchers.anyBoolean());
        verifyNoInteractions(connections, lobbies);
    }

    @Test
    void block_ofUnknownUser_reportsNotFound() {
        // Given
        when(userRepo.setBlocked(TARGET, true)).thenReturn(false);

        // When
        AdminUserService.BlockResult result = service.setBlocked(ADMIN, TARGET, true);

        // Then
        assertEquals(AdminUserService.BlockOutcome.NOT_FOUND, result.outcome());
        verifyNoInteractions(connections, lobbies);
    }

    @Test
    void block_stillSucceeds_whenCleanupFails() {
        // Given: the flag is stored, but API Gateway calls fail
        when(userRepo.setBlocked(TARGET, true)).thenReturn(true);
        doThrow(SdkException.create("boom", null)).when(connections).disconnectUser(TARGET);
        doThrow(SdkException.create("boom", null)).when(lobbies).removeFromUnstartedLobbies(TARGET);

        // When
        AdminUserService.BlockResult result = service.setBlocked(ADMIN, TARGET, true);

        // Then
        assertEquals(AdminUserService.BlockOutcome.UPDATED, result.outcome());
        assertEquals(0, result.closedConnections());
        assertEquals(0, result.removedFromLobbies());
    }

    private static UserProfile profile(String userId, String username, double elo, Boolean blocked) {
        UserProfile profile = UserProfile.builder().userId(userId).username(username).eloScore(elo).build();
        profile.setBlocked(blocked);
        return profile;
    }
}
