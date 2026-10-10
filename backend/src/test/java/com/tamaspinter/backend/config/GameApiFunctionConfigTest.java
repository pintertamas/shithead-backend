package com.tamaspinter.backend.config;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameApiFunctionConfigTest {

    private GameFunctionConfig game;
    private AccountManagementFunctionConfig account;
    private VoiceFunctionConfig voice;
    private Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> joinGame;
    private Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> raiseDecks;
    private Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> getState;
    private Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> accountManagement;
    private Function<APIGatewayV2WebSocketEvent, APIGatewayProxyResponseEvent> playCardWS;
    private Function<APIGatewayV2WebSocketEvent, APIGatewayProxyResponseEvent> pickupPileWS;
    private Function<Map<String, Object>, Object> gameApi;

    @BeforeEach
    void setUp() {
        game = mock(GameFunctionConfig.class);
        account = mock(AccountManagementFunctionConfig.class);
        voice = mock(VoiceFunctionConfig.class);
        joinGame = restHandler("joinGame");
        getState = restHandler("getState");
        accountManagement = restHandler("accountManagement");
        playCardWS = webSocketHandler("playCardWS");
        pickupPileWS = webSocketHandler("pickupPileWS");
        Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> leaveGame = restHandler("leaveGame");
        Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> startGame = restHandler("startGame");
        Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> leaderboardTop = restHandler("leaderboardTop");
        Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> leaderboardSession =
                restHandler("leaderboardSession");
        raiseDecks = restHandler("raiseDecks");

        when(game.joinGame()).thenReturn(joinGame);
        when(game.leaveGame()).thenReturn(leaveGame);
        when(game.startGame()).thenReturn(startGame);
        when(game.raiseDecks()).thenReturn(raiseDecks);
        when(game.getState()).thenReturn(getState);
        when(game.leaderboardTop()).thenReturn(leaderboardTop);
        when(game.leaderboardSession()).thenReturn(leaderboardSession);
        when(game.playCardWS()).thenReturn(playCardWS);
        when(game.pickupPileWS()).thenReturn(pickupPileWS);
        when(account.accountManagement()).thenReturn(accountManagement);
        Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> voiceToken = restHandler("voiceToken");
        when(voice.voiceToken()).thenReturn(voiceToken);

        gameApi = new GameApiFunctionConfig(new ObjectMapper(), new ApiRoutes(game, account, voice)).gameApi();
    }

    @Test
    void restPostRoutesToJoinGame() {
        APIGatewayProxyResponseEvent response = invoke(restEvent("POST", "/join-game", null));

        assertEquals("joinGame", response.getBody());
        verify(joinGame).apply(any(APIGatewayProxyRequestEvent.class));
    }

    @Test
    void restPostToGameDecksRoutesToRaiseDecks() {
        // Given: a session id in the path

        // When
        APIGatewayProxyResponseEvent response = invoke(restEvent("POST", "/games/ABC123/decks", null));

        // Then
        assertEquals("raiseDecks", response.getBody());
        verify(raiseDecks).apply(any(APIGatewayProxyRequestEvent.class));
    }

    @Test
    void restPathTemplateRouteFillsPathParametersWhenMissing() {
        invoke(restEvent("GET", "/state/ABC123", null));

        ArgumentCaptor<APIGatewayProxyRequestEvent> captor = ArgumentCaptor.forClass(APIGatewayProxyRequestEvent.class);
        verify(getState).apply(captor.capture());
        assertEquals("ABC123", captor.getValue().getPathParameters().get("sessionId"));
    }

    @Test
    void restKeepsPathParametersSuppliedByApiGateway() {
        invoke(restEvent("GET", "/state/ABC123", Map.of("sessionId", "FROM-GATEWAY")));

        ArgumentCaptor<APIGatewayProxyRequestEvent> captor = ArgumentCaptor.forClass(APIGatewayProxyRequestEvent.class);
        verify(getState).apply(captor.capture());
        assertEquals("FROM-GATEWAY", captor.getValue().getPathParameters().get("sessionId"));
    }

    @Test
    void profileAndAdminRoutesGoToAccountManagement() {
        assertEquals("accountManagement", invoke(restEvent("GET", "/profile", null)).getBody());
        assertEquals("accountManagement", invoke(restEvent("PUT", "/profile", null)).getBody());
        assertEquals("accountManagement", invoke(restEvent("DELETE", "/profile", null)).getBody());
        assertEquals("accountManagement", invoke(restEvent("POST", "/admin/doomsday", null)).getBody());
        verify(accountManagement, org.mockito.Mockito.times(4)).apply(any(APIGatewayProxyRequestEvent.class));
    }

    @Test
    void webSocketPlaySetupChatAndNudgeRouteToPlayCardWS() {
        assertEquals("playCardWS", invoke(wsEvent("play", "{\"action\":\"play\"}")).getBody());
        assertEquals("playCardWS", invoke(wsEvent("setup", "{\"action\":\"setup\"}")).getBody());
        assertEquals("playCardWS", invoke(wsEvent("chat", "{\"action\":\"chat\"}")).getBody());
        assertEquals("playCardWS", invoke(wsEvent("nudge", "{\"action\":\"nudge\"}")).getBody());
        verify(playCardWS, org.mockito.Mockito.times(4)).apply(any(APIGatewayV2WebSocketEvent.class));
        verify(pickupPileWS, never()).apply(any(APIGatewayV2WebSocketEvent.class));
    }

    @Test
    void webSocketPickupRoutesToPickupPileWS() {
        assertEquals("pickupPileWS", invoke(wsEvent("pickup", "{\"action\":\"pickup\"}")).getBody());
        verify(pickupPileWS).apply(any(APIGatewayV2WebSocketEvent.class));
        verify(playCardWS, never()).apply(any(APIGatewayV2WebSocketEvent.class));
    }

    @Test
    void webSocketFallsBackToBodyActionWhenRouteKeyIsMissing() {
        assertEquals("pickupPileWS", invoke(wsEvent(null, "{\"action\":\"pickup\"}")).getBody());
        verify(pickupPileWS).apply(any(APIGatewayV2WebSocketEvent.class));
    }

    @Test
    void unknownRestRouteReturnsNotFoundWithCorsHeaders() {
        APIGatewayProxyResponseEvent response = invoke(restEvent("GET", "/no-such-route", null));

        assertEquals(404, response.getStatusCode());
        assertNotNull(response.getHeaders().get("Access-Control-Allow-Origin"));
    }

    @Test
    void knownPathWithUnsupportedMethodReturnsNotFound() {
        assertEquals(404, invoke(restEvent("PATCH", "/profile", null)).getStatusCode());
        verify(accountManagement, never()).apply(any(APIGatewayProxyRequestEvent.class));
    }

    @Test
    void unknownWebSocketRouteReturnsNotFound() {
        assertEquals(404, invoke(wsEvent("nonsense", "{}")).getStatusCode());
    }

    @Test
    void unrecognisedEventShapeReturnsNotFound() {
        assertEquals(404, invoke(Map.of("something", "else")).getStatusCode());
    }

    @Test
    void routeTableMatchesPathTemplatesOnlyForTheirMethod() {
        ApiRoutes routes = new ApiRoutes(game, account, voice);

        assertTrue(routes.findRest("GET", "/leaderboard/session/XYZ").isPresent());
        assertEquals(false, routes.findRest("GET", "/leaderboard/session").isPresent());
        assertTrue(routes.findRest("GET", "/admin/users").isPresent());
        assertTrue(routes.findRest("POST", "/admin/users/abc/block").isPresent());
        assertTrue(routes.findRest("POST", "/admin/users/abc/unblock").isPresent());
        assertTrue(routes.findRest("GET", "/games").isPresent());
        assertTrue(routes.findRest("POST", "/games/ABC123/voice-token").isPresent());
        assertEquals(false, routes.findRest("GET", "/games/ABC123/voice-token").isPresent());
        assertTrue(routes.findRest("POST", "/games/ABC123/decks").isPresent());
        assertEquals(false, routes.findRest("GET", "/games/ABC123/decks").isPresent());
        assertEquals(false, routes.findRest("GET", "/admin/users/abc/block").isPresent());
        assertEquals(false, routes.findWebSocket("unknown").isPresent());
    }

    private APIGatewayProxyResponseEvent invoke(Map<String, Object> event) {
        return (APIGatewayProxyResponseEvent) gameApi.apply(event);
    }

    private static Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> restHandler(String name) {
        @SuppressWarnings("unchecked")
        Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> handler = mock(Function.class);
        when(handler.apply(any())).thenReturn(new APIGatewayProxyResponseEvent().withStatusCode(200).withBody(name));
        return handler;
    }

    private static Function<APIGatewayV2WebSocketEvent, APIGatewayProxyResponseEvent> webSocketHandler(String name) {
        @SuppressWarnings("unchecked")
        Function<APIGatewayV2WebSocketEvent, APIGatewayProxyResponseEvent> handler = mock(Function.class);
        when(handler.apply(any())).thenReturn(new APIGatewayProxyResponseEvent().withStatusCode(200).withBody(name));
        return handler;
    }

    private static Map<String, Object> restEvent(String method, String path, Map<String, String> pathParameters) {
        Map<String, Object> event = new HashMap<>();
        event.put("httpMethod", method);
        event.put("path", path);
        event.put("body", "{}");
        if (pathParameters != null) {
            event.put("pathParameters", pathParameters);
        }
        event.put("requestContext", Map.of("authorizer", Map.of("claims", Map.of("sub", "user-1"))));
        return event;
    }

    private static Map<String, Object> wsEvent(String routeKey, String body) {
        Map<String, Object> requestContext = new HashMap<>();
        requestContext.put("routeKey", routeKey);
        requestContext.put("eventType", "MESSAGE");
        requestContext.put("connectionId", "conn-1");
        requestContext.put("domainName", "abc.execute-api.eu-central-1.amazonaws.com");
        requestContext.put("stage", "prod");
        Map<String, Object> event = new HashMap<>();
        event.put("body", body);
        event.put("requestContext", requestContext);
        return event;
    }
}
