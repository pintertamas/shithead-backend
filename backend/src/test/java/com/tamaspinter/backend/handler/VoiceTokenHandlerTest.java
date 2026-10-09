package com.tamaspinter.backend.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamaspinter.backend.entity.GameConfigEntity;
import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.entity.PlayerEntity;
import com.tamaspinter.backend.repository.GameSessionRepository;
import com.tamaspinter.backend.service.BlockedUserGuard;
import com.tamaspinter.backend.service.LiveKitAccessTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoiceTokenHandlerTest {

    private static final String SESSION = "ABC123";
    private static final String PLAYER = "player-1";
    private static final String OUTSIDER = "outsider-9";

    private final ObjectMapper mapper = new ObjectMapper();
    private GameSessionRepository sessions;
    private BlockedUserGuard blockedUserGuard;
    private LiveKitAccessTokenService tokens;
    private VoiceTokenHandler handler;

    @BeforeEach
    void setUp() {
        sessions = mock(GameSessionRepository.class);
        blockedUserGuard = mock(BlockedUserGuard.class);
        tokens = mock(LiveKitAccessTokenService.class);
        when(tokens.isConfigured()).thenReturn(true);
        when(tokens.serverUrl()).thenReturn("wss://example.livekit.cloud");
        when(tokens.createToken(anyString(), anyString(), anyString())).thenReturn("header.payload.sig");
        handler = new VoiceTokenHandler(sessions, blockedUserGuard, tokens, mapper);
    }

    @Test
    void playerInVoiceGameGetsUrlTokenAndRoom() throws Exception {
        when(sessions.get(SESSION)).thenReturn(game(true, PLAYER));

        APIGatewayProxyResponseEvent response = handler.issueToken(request(PLAYER, SESSION));

        assertEquals(200, response.getStatusCode());
        JsonNode body = mapper.readTree(response.getBody());
        assertEquals("wss://example.livekit.cloud", body.get("url").asText());
        assertEquals("header.payload.sig", body.get("token").asText());
        assertEquals(SESSION, body.get("room").asText());
        verify(tokens).createToken(SESSION, PLAYER, "Tomi");
    }

    @Test
    void nonPlayerGetsForbiddenAndNoToken() {
        when(sessions.get(SESSION)).thenReturn(game(true, PLAYER));

        APIGatewayProxyResponseEvent response = handler.issueToken(request(OUTSIDER, SESSION));

        assertEquals(403, response.getStatusCode());
        verify(tokens, never()).createToken(anyString(), anyString(), anyString());
    }

    @Test
    void voiceDisabledGameGetsForbidden() {
        when(sessions.get(SESSION)).thenReturn(game(false, PLAYER));

        APIGatewayProxyResponseEvent response = handler.issueToken(request(PLAYER, SESSION));

        assertEquals(403, response.getStatusCode());
        assertTrue(response.getBody().contains("not enabled"));
        verify(tokens, never()).createToken(anyString(), anyString(), anyString());
    }

    @Test
    void gameWithoutConfigIsTreatedAsVoiceDisabled() {
        GameSessionEntity game = game(true, PLAYER);
        game.setConfig(null);
        when(sessions.get(SESSION)).thenReturn(game);

        assertEquals(403, handler.issueToken(request(PLAYER, SESSION)).getStatusCode());
    }

    @Test
    void missingLiveKitSettingsReturnServiceUnavailable() {
        when(sessions.get(SESSION)).thenReturn(game(true, PLAYER));
        when(tokens.isConfigured()).thenReturn(false);

        APIGatewayProxyResponseEvent response = handler.issueToken(request(PLAYER, SESSION));

        assertEquals(503, response.getStatusCode());
        assertEquals("{\"message\":\"Voice chat is not configured\"}", response.getBody());
        verify(tokens, never()).createToken(anyString(), anyString(), anyString());
    }

    @Test
    void unknownGameReturnsNotFound() {
        when(sessions.get("NOPE00")).thenReturn(null);

        assertEquals(404, handler.issueToken(request(PLAYER, "NOPE00")).getStatusCode());
    }

    @Test
    void blockedUserIsForbidden() {
        when(blockedUserGuard.isBlocked(PLAYER)).thenReturn(true);

        APIGatewayProxyResponseEvent response = handler.issueToken(request(PLAYER, SESSION));

        assertEquals(403, response.getStatusCode());
        assertEquals(BlockedUserGuard.BLOCKED_BODY, response.getBody());
        verify(sessions, never()).get(anyString());
    }

    @Test
    void responseNeverEchoesTheSecretOrTheAccountOfAnotherUser() {
        when(sessions.get(SESSION)).thenReturn(game(false, PLAYER));

        APIGatewayProxyResponseEvent response = handler.issueToken(request(OUTSIDER, SESSION));

        assertFalse(response.getBody().contains(PLAYER));
        assertNotNull(response.getHeaders().get("Access-Control-Allow-Origin"));
    }

    private static GameSessionEntity game(boolean voiceEnabled, String playerId) {
        GameConfigEntity config = GameConfigEntity.builder().voiceEnabled(voiceEnabled).build();
        PlayerEntity player = PlayerEntity.builder().playerId(playerId).username("Tomi").build();
        return GameSessionEntity.builder()
                .sessionId(SESSION)
                .players(List.of(player))
                .config(config)
                .build();
    }

    private static APIGatewayProxyRequestEvent request(String userId, String sessionId) {
        APIGatewayProxyRequestEvent.ProxyRequestContext context = new APIGatewayProxyRequestEvent.ProxyRequestContext();
        context.setAuthorizer(Map.<String, Object>of("claims", Map.of("sub", userId)));
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent()
                .withPathParameters(Map.of("sessionId", sessionId));
        request.setRequestContext(context);
        return request;
    }
}
