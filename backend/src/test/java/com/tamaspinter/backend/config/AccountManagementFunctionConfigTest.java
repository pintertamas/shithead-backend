package com.tamaspinter.backend.config;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamaspinter.backend.repository.GameSessionRepository;
import com.tamaspinter.backend.handler.AdminUserHandler;
import com.tamaspinter.backend.handler.GameBrowseHandler;
import com.tamaspinter.backend.repository.UserProfileRepository;
import com.tamaspinter.backend.service.AdminUserService;
import com.tamaspinter.backend.service.BlockedUserGuard;
import com.tamaspinter.backend.service.GameBrowseService;
import com.tamaspinter.backend.service.UserProfileService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountManagementFunctionConfigTest {

    private static final String ADMIN_GROUP = "game-admin";
    private static final String ADMIN_SUB = "admin-1";
    private static final String PLAYER_SUB = "player-2";
    private static final String TARGET = "target-9";

    private AdminUserService adminUsers;
    private GameBrowseService gameBrowse;
    private BlockedUserGuard blockedUserGuard;
    private UserProfileService profileService;
    private Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> lambda;

    @BeforeAll
    static void configureRegion() {
        // The config builds a DynamoDB client eagerly; the SDK only needs a region to construct it.
        System.setProperty("aws.region", "eu-central-1");
    }

    @BeforeEach
    void setUp() {
        adminUsers = mock(AdminUserService.class);
        gameBrowse = mock(GameBrowseService.class);
        blockedUserGuard = mock(BlockedUserGuard.class);
        profileService = mock(UserProfileService.class);
        ObjectMapper mapper = new ObjectMapper();
        AccountManagementFunctionConfig config = new AccountManagementFunctionConfig(
                mock(GameSessionRepository.class),
                mock(UserProfileRepository.class),
                profileService,
                blockedUserGuard,
                new AdminUserHandler(adminUsers, mapper),
                new GameBrowseHandler(gameBrowse, mapper),
                mapper);
        lambda = config.accountManagement();
    }

    @Test
    void nonAdmin_cannotListUsers() {
        // Given
        APIGatewayProxyRequestEvent request = request("GET", "/admin/users", PLAYER_SUB, List.of());

        // When
        APIGatewayProxyResponseEvent response = lambda.apply(request);

        // Then
        assertEquals(403, response.getStatusCode());
        assertTrue(response.getBody().contains("Administrator access is required"));
        verify(adminUsers, never()).listUsers();
    }

    @Test
    void admin_listsUsers() {
        // Given
        when(adminUsers.listUsers()).thenReturn(List.of(new AdminUserService.AdminUserView("u1", "Alice", 1010, true)));
        APIGatewayProxyRequestEvent request = request("GET", "/prod/admin/users", ADMIN_SUB, List.of(ADMIN_GROUP));

        // When
        APIGatewayProxyResponseEvent response = lambda.apply(request);

        // Then
        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("\"userId\":\"u1\""));
        assertTrue(response.getBody().contains("\"blocked\":true"));
    }

    @Test
    void admin_blocksTarget_andReportsCleanup() {
        // Given
        when(adminUsers.setBlocked(ADMIN_SUB, TARGET, true))
                .thenReturn(new AdminUserService.BlockResult(AdminUserService.BlockOutcome.UPDATED, 2, 1));
        APIGatewayProxyRequestEvent request = request("POST", "/admin/users/" + TARGET + "/block", ADMIN_SUB,
                List.of(ADMIN_GROUP));

        // When
        APIGatewayProxyResponseEvent response = lambda.apply(request);

        // Then
        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("\"closedConnections\":2"));
        assertTrue(response.getBody().contains("\"removedFromLobbies\":1"));
    }

    @Test
    void admin_unblocksTarget() {
        // Given
        when(adminUsers.setBlocked(ADMIN_SUB, TARGET, false))
                .thenReturn(new AdminUserService.BlockResult(AdminUserService.BlockOutcome.UPDATED, 0, 0));
        APIGatewayProxyRequestEvent request = request("POST", "/admin/users/" + TARGET + "/unblock", ADMIN_SUB,
                List.of(ADMIN_GROUP));

        // When
        APIGatewayProxyResponseEvent response = lambda.apply(request);

        // Then
        assertEquals(200, response.getStatusCode());
        verify(adminUsers).setBlocked(ADMIN_SUB, TARGET, false);
    }

    @Test
    void admin_blockingSelf_isRefused() {
        // Given
        when(adminUsers.setBlocked(ADMIN_SUB, ADMIN_SUB, true))
                .thenReturn(new AdminUserService.BlockResult(AdminUserService.BlockOutcome.SELF_BLOCK_REFUSED, 0, 0));
        APIGatewayProxyRequestEvent request = request("POST", "/admin/users/" + ADMIN_SUB + "/block", ADMIN_SUB,
                List.of(ADMIN_GROUP));

        // When
        APIGatewayProxyResponseEvent response = lambda.apply(request);

        // Then
        assertEquals(400, response.getStatusCode());
    }

    @Test
    void nonAdmin_cannotBlockOrUnblock() {
        // Given
        APIGatewayProxyRequestEvent block = request("POST", "/admin/users/" + TARGET + "/block", PLAYER_SUB, List.of());
        APIGatewayProxyRequestEvent unblock = request("POST", "/admin/users/" + TARGET + "/unblock", PLAYER_SUB, List.of());

        // When / Then
        assertEquals(403, lambda.apply(block).getStatusCode());
        assertEquals(403, lambda.apply(unblock).getStatusCode());
        verify(adminUsers, never()).setBlocked(anyString(), anyString(), anyBoolean());
    }

    @Test
    void blockedUser_isRejectedBeforeAnyRoute() {
        // Given
        when(blockedUserGuard.isBlocked(PLAYER_SUB)).thenReturn(true);
        APIGatewayProxyRequestEvent request = request("GET", "/profile", PLAYER_SUB, List.of());

        // When
        APIGatewayProxyResponseEvent response = lambda.apply(request);

        // Then
        assertEquals(403, response.getStatusCode());
        assertEquals("{\"message\":\"Your account has been blocked.\"}", response.getBody());
        verify(profileService, never()).getOrCreateProfile(anyString(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void games_routeReturnsOpenGames() {
        // Given
        when(gameBrowse.listOpenGames()).thenReturn(List.of(new GameBrowseService.OpenGameView(
                "g1", "Alice", 2, 5, "waiting", 1, "2026-10-09T10:00:00Z")));
        APIGatewayProxyRequestEvent request = request("GET", "/prod/games", PLAYER_SUB, List.of());

        // When
        APIGatewayProxyResponseEvent response = lambda.apply(request);

        // Then
        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("\"sessionId\":\"g1\""));
        assertTrue(response.getBody().contains("\"maxPlayers\":5"));
    }

    private static APIGatewayProxyRequestEvent request(String method, String path, String sub, List<String> groups) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", sub);
        claims.put("cognito:groups", groups);
        APIGatewayProxyRequestEvent.ProxyRequestContext context = new APIGatewayProxyRequestEvent.ProxyRequestContext();
        context.setAuthorizer(Map.of("claims", claims));
        return new APIGatewayProxyRequestEvent()
                .withHttpMethod(method)
                .withPath(path)
                .withRequestContext(context);
    }
}
