package com.tamaspinter.backend.config;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.entity.PlayerEntity;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.game.PlayResult;
import com.tamaspinter.backend.mapper.SessionMapper;
import com.tamaspinter.backend.model.Player;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.game.CardSource;
import com.tamaspinter.backend.game.ChatMessageValidator;
import com.tamaspinter.backend.model.UserProfile;
import com.tamaspinter.backend.model.api.GameStateView;
import com.tamaspinter.backend.model.api.LeaderboardEntry;
import com.tamaspinter.backend.model.api.PlayerStateView;
import com.tamaspinter.backend.model.websocket.PickupMessage;
import com.tamaspinter.backend.model.websocket.PlayMessage;
import com.tamaspinter.backend.repository.GameSessionRepository;
import com.tamaspinter.backend.repository.UserProfileRepository;
import com.tamaspinter.backend.service.BlockedUserGuard;
import com.tamaspinter.backend.service.EloService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiClient;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.GoneException;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.PostToConnectionRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Configuration
@RequiredArgsConstructor
@SuppressWarnings({"PMD.GodClass", "PMD.TooManyMethods", "PMD.ExcessiveImports"})
public class GameFunctionConfig {

    private static final Map<String, String> CORS_HEADERS = Map.of(
            "Access-Control-Allow-Origin", "*",
            "Access-Control-Allow-Headers", "Content-Type,Authorization",
            "Access-Control-Allow-Methods", "POST,GET,PUT,OPTIONS"
    );

    private final GameSessionRepository sessionRepo;
    private final UserProfileRepository userRepo;
    private final BlockedUserGuard blockedUserGuard;
    private final ObjectMapper mapper;
    private final DynamoDbClient dynamoClient = DynamoDbClient.create();
    private final String wsConnectionsTable = System.getenv("WS_CONNECTIONS_TABLE");

    private static APIGatewayProxyResponseEvent corsResponse(int statusCode) {
        return new APIGatewayProxyResponseEvent().withStatusCode(statusCode).withHeaders(CORS_HEADERS);
    }

    private static APIGatewayProxyResponseEvent corsResponse(int statusCode, String body) {
        return new APIGatewayProxyResponseEvent().withStatusCode(statusCode).withHeaders(CORS_HEADERS).withBody(body);
    }

    private void cleanupOldSessions(String userId, String excludeSessionId) {
        for (GameSessionEntity owned : sessionRepo.findByOwnerId(userId)) {
            if (owned.isStarted() || owned.getSessionId().equals(excludeSessionId)) {
                continue;
            }
            GameSession session = SessionMapper.fromEntity(owned);
            session.removePlayer(userId);
            if (session.getPlayers().isEmpty()) {
                sessionRepo.delete(owned.getSessionId());
            } else {
                sessionRepo.save(session.toEntity());
            }
        }
    }

    @Bean
    @SuppressWarnings("PMD.CognitiveComplexity")
    public Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> joinGame() {
        return req -> {
            Map<?, ?> data;
            try {
                data = mapper.readValue(req.getBody(), Map.class);
            } catch (JsonProcessingException e) {
                return corsResponse(400);
            }
            String sessionId = (String) data.get("sessionId");

            GameSessionEntity entity = sessionRepo.get(sessionId);
            if (entity == null) {
                return corsResponse(404);
            }
            if (entity.isStarted()) {
                return corsResponse(409);
            }
            @SuppressWarnings("unchecked")
            Map<String, String> claims = (Map<String, String>) req.getRequestContext().getAuthorizer().get("claims");
            String userId = claims.get("sub");
            if (blockedUserGuard.isBlocked(userId)) {
                return corsResponse(403, BlockedUserGuard.BLOCKED_BODY);
            }
            if (entity.getPlayers() != null
                    && entity.getPlayers().stream().anyMatch(player -> userId.equals(player.getPlayerId()))) {
                return corsResponse(200);
            }

            cleanupOldSessions(userId, sessionId);

            UserProfile user = userRepo.get(userId);
            String username = user != null ? user.getUsername() : "Unknown";
            GameSession session = SessionMapper.fromEntity(entity);
            try {
                session.addPlayer(userId, username);
            } catch (IllegalStateException e) {
                return corsResponse(409);
            }
            sessionRepo.save(session.toEntity());
            return corsResponse(200);
        };
    }

    @SuppressWarnings("PMD.CognitiveComplexity")
    @Bean
    public Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> startGame() {
        return req -> {
            Map<?, ?> data;
            try {
                data = mapper.readValue(req.getBody(), Map.class);
            } catch (JsonProcessingException e) {
                return corsResponse(400);
            }
            String sessionId = (String) data.get("sessionId");

            GameSessionEntity entity = sessionRepo.get(sessionId);
            if (entity == null) {
                return corsResponse(404);
            }
            @SuppressWarnings("unchecked")
            Map<String, String> claims = (Map<String, String>) req.getRequestContext().getAuthorizer().get("claims");
            String userId = claims.get("sub");
            if (blockedUserGuard.isBlocked(userId)) {
                return corsResponse(403, BlockedUserGuard.BLOCKED_BODY);
            }
            if (!userId.equals(entity.getOwnerId())) {
                return corsResponse(403);
            }

            if (entity.getPlayers().size() < 2) {
                return corsResponse(400);
            }
            String phase = (String) data.get("phase");
            if ("prepare".equals(phase)) {
                if (!entity.isStarted() && !entity.isStarting()) {
                    entity.setStarting(true);
                    sessionRepo.save(entity);
                }
                return corsResponse(200, "{\"starting\":true}");
            }
            if (entity.isStarted()) {
                return corsResponse(200);
            }
            GameSession session = SessionMapper.fromEntity(entity);
            try {
                session.start();
            } catch (IllegalStateException e) {
                if (entity.isStarting()) {
                    entity.setStarting(false);
                    sessionRepo.save(entity);
                }
                return corsResponse(409);
            }
            sessionRepo.save(session.toEntity());
            return corsResponse(200);
        };
    }

    @Bean
    public Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> leaveGame() {
        return req -> {
            Map<?, ?> data;
            try {
                data = mapper.readValue(req.getBody(), Map.class);
            } catch (JsonProcessingException e) {
                return corsResponse(400);
            }
            String sessionId = (String) data.get("sessionId");

            GameSessionEntity entity = sessionRepo.get(sessionId);
            if (entity == null) {
                return corsResponse(404);
            }
            if (entity.isStarted()) {
                return corsResponse(409, "{\"error\":\"Cannot leave a started game\"}");
            }
            @SuppressWarnings("unchecked")
            Map<String, String> claims = (Map<String, String>) req.getRequestContext().getAuthorizer().get("claims");
            String userId = claims.get("sub");
            if (blockedUserGuard.isBlocked(userId)) {
                return corsResponse(403, BlockedUserGuard.BLOCKED_BODY);
            }

            GameSession session = SessionMapper.fromEntity(entity);
            session.removePlayer(userId);

            if (session.getPlayers().isEmpty()) {
                sessionRepo.delete(sessionId);
            } else {
                sessionRepo.save(session.toEntity());
            }
            return corsResponse(200);
        };
    }

    @Bean
    public Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> getState() {
        return req -> {
            String sessionId = req.getPathParameters().get("sessionId");
            GameSessionEntity entity = sessionRepo.get(sessionId);
            if (entity == null) {
                return corsResponse(404);
            }
            @SuppressWarnings("unchecked")
            Map<String, String> claims = (Map<String, String>) req.getRequestContext().getAuthorizer().get("claims");
            String userId = claims.get("sub");
            if (blockedUserGuard.isBlocked(userId)) {
                return corsResponse(403, BlockedUserGuard.BLOCKED_BODY);
            }
            try {
                GameStateView view = buildGameStateView(entity, userId);
                return corsResponse(200, mapper.writeValueAsString(view));
            } catch (JsonProcessingException e) {
                log.error("getState serialization failed", e);
                return corsResponse(500);
            }
        };
    }

    @Bean
    @SuppressWarnings("PMD.CognitiveComplexity")
    public Function<APIGatewayV2WebSocketEvent, APIGatewayProxyResponseEvent> playCardWS() {
        return ev -> {
            PlayMessage msg;
            try {
                msg = mapper.readValue(ev.getBody(), PlayMessage.class);
            } catch (JsonProcessingException e) {
                return websocketError(ev, 400, "Couldn't read the play request. Please try again.");
            }

            GameSessionEntity entity = sessionRepo.get(msg.sessionId());
            if (entity == null) {
                return websocketError(ev, 404, "This game session no longer exists.");
            }

            String userId = websocketUserId(ev);
            if (blockedUserGuard.isBlocked(userId)) {
                return websocketError(ev, 403, BlockedUserGuard.BLOCKED_MESSAGE);
            }
            if (userId == null || entity.getPlayers() == null || entity.getPlayers().stream()
                    .noneMatch(player -> userId.equals(player.getPlayerId()))) {
                return websocketError(ev, 403, "Join this game before sending actions.");
            }
            if ("chat".equals(msg.action())) {
                return handleChatAction(ev, msg, entity, userId);
            }
            if ("setup".equals(msg.action())) {
                return handleSetupAction(ev, msg, entity, userId);
            }
            if (!userId.equals(entity.getCurrentPlayerId())) {
                return websocketError(ev, 400, "It is not your turn.");
            }

            final Card revealedCard = revealedSelectionCard(msg, entity, userId);
            GameSession session = SessionMapper.fromEntity(entity);
            PlayResult result = msg.selections() == null || msg.selections().isEmpty()
                    ? session.playCards(msg.cards())
                    : session.playSelections(msg.selections());
            if (result == PlayResult.INVALID) {
                return websocketError(ev, 400,
                        "That play can't be made right now. Check that it's your turn and the cards are allowed.");
            }

            GameSessionEntity updated = session.toEntity();
            sessionRepo.save(updated);
            if (session.isFinished() && !entity.isEloUpdated() && updateElo(session)) {
                updated.setEloUpdated(true);
                sessionRepo.save(updated);
            }
            String endpoint = websocketEndpoint(ev);
            broadcastState(msg.sessionId(), updated, endpoint,
                    result == PlayResult.PICKUP ? revealedCard : null);

            return new APIGatewayProxyResponseEvent().withStatusCode(200);
        };
    }

    /**
     * Relays a session chat message to every connection of the game. Nothing is stored or logged. Rate limiting
     * is left to API Gateway's route throttling (see the stage settings in infra/terraform/api_gateway).
     */
    private APIGatewayProxyResponseEvent handleChatAction(
            APIGatewayV2WebSocketEvent event, PlayMessage message, GameSessionEntity entity, String userId) {
        ChatMessageValidator.Result checked = ChatMessageValidator.validate(message.text());
        if (checked.status() == ChatMessageValidator.Status.EMPTY) {
            return new APIGatewayProxyResponseEvent().withStatusCode(200);
        }
        if (checked.status() == ChatMessageValidator.Status.TOO_LONG) {
            return websocketError(event, 400,
                    "Chat messages are limited to " + ChatMessageValidator.MAX_LENGTH + " characters.");
        }
        String username = entity.getPlayers().stream()
                .filter(player -> userId.equals(player.getPlayerId()))
                .map(PlayerEntity::getUsername)
                .findFirst()
                .orElse("Unknown");
        Map<String, Object> chat = Map.of(
                "type", "chat",
                "userId", userId,
                "username", username,
                "text", checked.text(),
                "ts", System.currentTimeMillis());
        postToGameConnections(message.sessionId(), websocketEndpoint(event), recipient -> chat);
        return new APIGatewayProxyResponseEvent().withStatusCode(200);
    }

    private APIGatewayProxyResponseEvent handleSetupAction(
            APIGatewayV2WebSocketEvent event, PlayMessage message, GameSessionEntity entity, String userId) {
        if ("announce".equals(message.setupAction())) {
            return handleStartAnnouncement(event, message, entity, userId);
        }

        GameSession session = SessionMapper.fromEntity(entity);
        boolean accepted;
        if ("swap".equals(message.setupAction()) && message.handIndex() != null && message.faceUpIndex() != null) {
            accepted = session.swapStartingCards(userId, message.handIndex(), message.faceUpIndex());
        } else if ("ready".equals(message.setupAction())) {
            accepted = session.markReady(userId);
        } else {
            accepted = false;
        }
        if (!accepted) {
            return websocketError(event, 400, "That setup action is no longer available.");
        }
        GameSessionEntity updated = session.toEntity();
        updated.setStarting(entity.isStarting());
        updated.setEloUpdated(entity.isEloUpdated());
        sessionRepo.save(updated);
        broadcastState(message.sessionId(), updated, websocketEndpoint(event));
        return new APIGatewayProxyResponseEvent().withStatusCode(200);
    }

    private APIGatewayProxyResponseEvent handleStartAnnouncement(
            APIGatewayV2WebSocketEvent event, PlayMessage message, GameSessionEntity entity, String userId) {
        if (!userId.equals(entity.getOwnerId())) {
            return websocketError(event, 403, "Only the game owner can start the game.");
        }
        if (entity.isStarted()) {
            return websocketError(event, 409, "The game has already started.");
        }
        if (!entity.isStarting()) {
            entity.setStarting(true);
            sessionRepo.save(entity);
        }
        broadcastState(message.sessionId(), entity, websocketEndpoint(event));
        return new APIGatewayProxyResponseEvent().withStatusCode(200);
    }

    /**
     * Card to reveal in the broadcast when a single face-down (blind flip) or face-up selection is made.
     * Only used when the play results in a pickup.
     */
    private Card revealedSelectionCard(PlayMessage message, GameSessionEntity entity, String userId) {
        if (message.selections() == null || message.selections().size() != 1) {
            return null;
        }
        CardSource source = message.selections().get(0).source();
        if (source != CardSource.FACE_DOWN && source != CardSource.FACE_UP) {
            return null;
        }
        int index = message.selections().get(0).index();
        return entity.getPlayers().stream()
                .filter(player -> userId.equals(player.getPlayerId()))
                .findFirst()
                .map(player -> source == CardSource.FACE_DOWN ? player.getFaceDown() : player.getFaceUp())
                .filter(cards -> cards != null && index >= 0 && index < cards.size())
                .map(cards -> SessionMapper.entitiesToCardList(List.of(cards.get(index))).get(0))
                .orElse(null);
    }

    private String websocketUserId(APIGatewayV2WebSocketEvent event) {
        if (wsConnectionsTable == null) {
            return null;
        }
        String connectionId = event.getRequestContext().getConnectionId();
        Map<String, AttributeValue> item = dynamoClient.getItem(GetItemRequest.builder()
                .tableName(wsConnectionsTable)
                .key(Map.of("connection_id", AttributeValue.fromS(connectionId)))
                .build()).item();
        return item != null && item.containsKey("user_id") ? item.get("user_id").s() : null;
    }

    private String websocketEndpoint(APIGatewayV2WebSocketEvent event) {
        return "https://" + event.getRequestContext().getDomainName()
                + "/" + event.getRequestContext().getStage();
    }

    @Bean
    public Function<APIGatewayV2WebSocketEvent, APIGatewayProxyResponseEvent> pickupPileWS() {
        return ev -> {
            PickupMessage msg;
            try {
                msg = mapper.readValue(ev.getBody(), PickupMessage.class);
            } catch (JsonProcessingException e) {
                return websocketError(ev, 400, "Couldn't read the pickup request. Please try again.");
            }

            GameSessionEntity entity = sessionRepo.get(msg.sessionId());
            if (entity == null) {
                return websocketError(ev, 404, "This game session no longer exists.");
            }

            String userId = websocketUserId(ev);
            if (blockedUserGuard.isBlocked(userId)) {
                return websocketError(ev, 403, BlockedUserGuard.BLOCKED_MESSAGE);
            }
            if (userId == null || !userId.equals(entity.getCurrentPlayerId())) {
                return websocketError(ev, 400, "It is not your turn.");
            }

            GameSession session = SessionMapper.fromEntity(entity);
            PlayResult result = session.pickupPile();
            if (result == PlayResult.INVALID) {
                return websocketError(ev, 400, "You can't pick up the pile right now. It may be empty or not your turn.");
            }

            GameSessionEntity updated = session.toEntity();
            sessionRepo.save(updated);
            String endpoint = "https://" + ev.getRequestContext().getDomainName()
                    + "/" + ev.getRequestContext().getStage();
            broadcastState(msg.sessionId(), updated, endpoint);

            return new APIGatewayProxyResponseEvent().withStatusCode(200);
        };
    }

    @Bean
    public Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> leaderboardSession() {
        return req -> {
            String sessionId = req.getPathParameters().get("sessionId");
            GameSessionEntity entity = sessionRepo.get(sessionId);
            if (entity == null) {
                return corsResponse(404);
            }
            List<String> playerIds = entity.getPlayers() == null
                    ? List.of()
                    : entity.getPlayers().stream().map(PlayerEntity::getPlayerId)
                            .collect(Collectors.toList());
            Map<String, UserProfile> profiles = userRepo.batchGet(playerIds)
                    .stream()
                    .collect(Collectors.toMap(UserProfile::getUserId, profile -> profile));
            List<LeaderboardEntry> entries = playerIds.stream()
                    .map(playerId -> profiles.getOrDefault(playerId, UserProfile.builder()
                            .userId(playerId)
                            .username("Unknown")
                            .eloScore(0)
                            .build()))
                    .map(profile -> LeaderboardEntry.builder()
                            .userId(profile.getUserId())
                            .username(profile.getUsername())
                            .eloScore(profile.getEloScore())
                            .build())
                    .collect(Collectors.toList());
            try {
                return corsResponse(200, mapper.writeValueAsString(entries));
            } catch (JsonProcessingException e) {
                log.error("leaderboardSession serialization failed", e);
                return corsResponse(500);
            }
        };
    }

    @Bean
    public Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> leaderboardTop() {
        return req -> {
            int limit = 20;
            String limitParam = req.getQueryStringParameters() != null
                    ? req.getQueryStringParameters().get("limit")
                    : null;
            if (limitParam != null) {
                try {
                    limit = Math.max(1, Math.min(100, Integer.parseInt(limitParam)));
                } catch (NumberFormatException ignored) {
                    limit = 20;
                }
            }
            List<LeaderboardEntry> entries = userRepo.topLeaderboard(limit).stream()
                    .map(profile -> LeaderboardEntry.builder()
                            .userId(profile.getUserId())
                            .username(profile.getUsername())
                            .eloScore(profile.getEloScore())
                            .build())
                    .collect(Collectors.toList());
            try {
                return corsResponse(200, mapper.writeValueAsString(entries));
            } catch (JsonProcessingException e) {
                log.error("leaderboardTop serialization failed", e);
                return corsResponse(500);
            }
        };
    }

    private boolean updateElo(GameSession session) {
        String shitheadId = session.getShitheadId();
        Map<String, Double> results = new HashMap<>();
        for (var player : session.getPlayers()) {
            results.put(player.getPlayerId(), player.getPlayerId().equals(shitheadId) ? 0.0 : 1.0);
        }
        List<String> playerIds = session.getPlayers().stream()
                .map(Player::getPlayerId)
                .collect(Collectors.toList());
        try {
            List<UserProfile> profiles = userRepo.batchGet(playerIds);
            Map<String, Double> current = profiles.stream()
                    .collect(Collectors.toMap(UserProfile::getUserId, UserProfile::getEloScore));
            if (current.size() < 2) {
                log.warn("Skipping Elo update for session {} because fewer than two profiles exist", session.getSessionId());
                return false;
            }
            Map<String, Double> updated = EloService.updateRatings(current, results);
            updated.forEach((id, elo) -> {
                UserProfile userProfile = userRepo.get(id);
                if (userProfile != null) {
                    userProfile.setEloScore(elo);
                    userRepo.save(userProfile);
                }
            });
            return true;
        } catch (SdkException e) {
            log.error("Elo update failed for session {}", session.getSessionId(), e);
            return false;
        }
    }

    private void broadcastState(String gameSessionId, GameSessionEntity state, String endpoint) {
        broadcastState(gameSessionId, state, endpoint, null);
    }

    private void broadcastState(String gameSessionId, GameSessionEntity state, String endpoint, Card revealedCard) {
        Map<String, Double> ratings = loadRatings(state);
        postToGameConnections(gameSessionId, endpoint,
                userId -> buildGameStateView(state, userId, ratings, revealedCard));
    }

    /**
     * Posts one payload to every connection registered for the game. The payload may differ per connection,
     * keyed by the connection's user ID. Connections API Gateway reports as gone are removed.
     */
    private void postToGameConnections(String gameSessionId, String endpoint, Function<String, Object> payloadForUser) {
        if (wsConnectionsTable == null) {
            log.warn("WS_CONNECTIONS_TABLE not set, skipping broadcast");
            return;
        }

        QueryResponse connections = dynamoClient.query(QueryRequest.builder()
                .tableName(wsConnectionsTable)
                .indexName("game_session_id-index")
                .keyConditionExpression("game_session_id = :gid")
                .expressionAttributeValues(Map.of(":gid", AttributeValue.fromS(gameSessionId)))
                .build());

        try (ApiGatewayManagementApiClient apigwClient = ApiGatewayManagementApiClient.builder()
                .endpointOverride(URI.create(endpoint))
                .build()) {
            for (Map<String, AttributeValue> item : connections.items()) {
                String connectionId = item.get("connection_id").s();
                String userId = item.containsKey("user_id") ? item.get("user_id").s() : null;
                try {
                    apigwClient.postToConnection(PostToConnectionRequest.builder()
                            .connectionId(connectionId)
                            .data(SdkBytes.fromByteArray(mapper.writeValueAsBytes(payloadForUser.apply(userId))))
                            .build());
                } catch (JsonProcessingException e) {
                    log.error("Failed to serialize WebSocket payload for broadcast", e);
                } catch (GoneException e) {
                    log.info("Removing stale connection: {}", connectionId);
                    dynamoClient.deleteItem(DeleteItemRequest.builder()
                            .tableName(wsConnectionsTable)
                            .key(Map.of("connection_id", AttributeValue.fromS(connectionId)))
                            .build());
                }
            }
        }
    }

    private APIGatewayProxyResponseEvent websocketError(
            APIGatewayV2WebSocketEvent event, int statusCode, String message) {
        String connectionId = event.getRequestContext().getConnectionId();
        String endpoint = "https://" + event.getRequestContext().getDomainName()
                + "/" + event.getRequestContext().getStage();
        try (ApiGatewayManagementApiClient client = ApiGatewayManagementApiClient.builder()
                .endpointOverride(URI.create(endpoint))
                .build()) {
            client.postToConnection(PostToConnectionRequest.builder()
                    .connectionId(connectionId)
                    .data(SdkBytes.fromByteArray(mapper.writeValueAsBytes(Map.of(
                            "type", "error",
                            "status", statusCode,
                            "message", message))))
                    .build());
        } catch (JsonProcessingException | SdkException e) {
            log.error("Failed to send WebSocket error to connection {}", connectionId, e);
        }
        return new APIGatewayProxyResponseEvent().withStatusCode(statusCode);
    }

    private Map<String, Double> loadRatings(GameSessionEntity entity) {
        List<String> playerIds = entity.getPlayers() == null ? List.of()
                : entity.getPlayers().stream().map(PlayerEntity::getPlayerId).toList();
        if (playerIds.isEmpty()) {
            return Map.of();
        }
        try {
            return userRepo.batchGet(playerIds).stream()
                    .collect(Collectors.toMap(UserProfile::getUserId, UserProfile::getEloScore));
        } catch (SdkException e) {
            log.warn("Could not load player ratings for game {}", entity.getSessionId(), e);
            return Map.of();
        }
    }

    private GameStateView buildGameStateView(GameSessionEntity entity, String viewerId) {
        return buildGameStateView(entity, viewerId, loadRatings(entity));
    }

    private GameStateView buildGameStateView(
            GameSessionEntity entity, String viewerId, Map<String, Double> ratings) {
        return buildGameStateView(entity, viewerId, ratings, null);
    }

    private GameStateView buildGameStateView(
            GameSessionEntity entity, String viewerId, Map<String, Double> ratings, Card revealedCard) {
        List<PlayerEntity> players = entity.getPlayers() == null
                ? List.of()
                : entity.getPlayers();
        List<PlayerStateView> playerViews = players.stream()
                .map(player -> {
                    boolean isYou = viewerId != null && viewerId.equals(player.getPlayerId());
                    List<com.tamaspinter.backend.entity.CardEntity> hand = player.getHand() == null
                            ? List.of()
                            : player.getHand();
                    List<com.tamaspinter.backend.entity.CardEntity> faceUp = player.getFaceUp() == null
                            ? List.of()
                            : player.getFaceUp();
                    List<com.tamaspinter.backend.entity.CardEntity> faceDown = player.getFaceDown() == null
                            ? List.of()
                            : player.getFaceDown();
                    return PlayerStateView.builder()
                            .playerId(player.getPlayerId())
                            .username(player.getUsername())
                            .handCount(hand.size())
                            .faceUp(SessionMapper.entitiesToCardList(faceUp))
                            .faceDownCount(faceDown.size())
                            .isYou(isYou)
                            .hand(isYou ? SessionMapper.entitiesToCardList(hand) : Collections.emptyList())
                            .eloScore(ratings.getOrDefault(player.getPlayerId(), 1000.0))
                            .ready(player.isReady())
                            .build();
                })
                .collect(Collectors.toList());

        List<com.tamaspinter.backend.entity.CardEntity> discard = entity.getDiscardPile() == null
                ? List.of()
                : entity.getDiscardPile();
        List<com.tamaspinter.backend.entity.CardEntity> deck = entity.getDeck() == null
                ? List.of()
                : entity.getDeck();

        return GameStateView.builder()
                .sessionId(entity.getSessionId())
                .started(entity.isStarted())
                .starting(entity.isStarting())
                .setupComplete(entity.isSetupComplete())
                .finished(entity.isFinished())
                .currentPlayerId(entity.getCurrentPlayerId())
                .shitheadId(entity.getShitheadId())
                .isOwner(viewerId != null && viewerId.equals(entity.getOwnerId()))
                .deckCount(deck.size())
                .allowMixedHandAndFaceUpWhenDeckEmpty(entity.getConfig() != null
                        && entity.getConfig().isAllowMixedHandAndFaceUpWhenDeckEmpty())
                .allowFailedFaceUpPlay(entity.getConfig() != null
                        && entity.getConfig().isAllowFailedFaceUpPlay())
                .revealedCard(revealedCard)
                .discardCount(discard.size())
                .discardPile(SessionMapper.entitiesToCardList(discard))
                .players(playerViews)
                .events(SessionMapper.entitiesToEvents(entity.getEvents()))
                .build();
    }
}
