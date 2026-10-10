package com.tamaspinter.backend.config;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamaspinter.backend.entity.EloChangeEntity;
import com.tamaspinter.backend.entity.GameConfigEntity;
import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.entity.PlayerEntity;
import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.game.PlayResult;
import com.tamaspinter.backend.mapper.SessionMapper;
import com.tamaspinter.backend.model.Player;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.game.CardSource;
import com.tamaspinter.backend.game.ChatMessageValidator;
import com.tamaspinter.backend.game.NudgeMessage;
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
    /** Deck count of a game that raised its deck, and the burn count the glue sets for two decks too. */
    private static final int TWO_DECKS = 2;
    private static final int TWO_DECKS_BURN_COUNT = 6;

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

    /** A 409 whose JSON body carries a message the client shows to the player. Messages must not contain quotes. */
    private static APIGatewayProxyResponseEvent conflictResponse(String message) {
        return corsResponse(409, "{\"message\":\"" + message + "\"}");
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
                return conflictResponse(e.getMessage());
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
                session.start(loadRatings(entity));
            } catch (IllegalStateException e) {
                if (entity.isStarting()) {
                    entity.setStarting(false);
                    sessionRepo.save(entity);
                }
                return conflictResponse(e.getMessage());
            }
            sessionRepo.save(session.toEntity());
            return corsResponse(200);
        };
    }

    /**
     * Owner-only, before the start: raises a one-deck lobby to two decks so it can seat more players.
     * Body {@code {"decksCount": 2}}; the burn count becomes 6, as for any two-deck game.
     */
    @Bean
    @SuppressWarnings("PMD.CognitiveComplexity")
    public Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> raiseDecks() {
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
            if (userId == null || !userId.equals(entity.getOwnerId())) {
                return corsResponse(403);
            }
            if (entity.isStarted()) {
                return conflictResponse("Game already started");
            }
            if (!isRaiseToTwoDecks(req.getBody())) {
                return corsResponse(400);
            }
            GameConfigEntity config = entity.getConfig() != null
                    ? entity.getConfig()
                    : GameConfig.defaultGameConfig().toEntity();
            if (config.getDecksCount() >= TWO_DECKS) {
                return conflictResponse("This game already uses two decks");
            }
            config.setDecksCount(TWO_DECKS);
            config.setBurnCount(TWO_DECKS_BURN_COUNT);
            entity.setConfig(config);
            sessionRepo.save(entity);
            return corsResponse(200, "{\"decksCount\":" + TWO_DECKS + ",\"burnCount\":" + TWO_DECKS_BURN_COUNT + "}");
        };
    }

    /**
     * True only for a body whose {@code decksCount} is the integer 2. Any other value, or no body, is false.
     */
    private boolean isRaiseToTwoDecks(String body) {
        if (body == null) {
            return false;
        }
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode requested = root == null ? null : root.get("decksCount");
            return requested != null && requested.isInt() && requested.intValue() == TWO_DECKS;
        } catch (JsonProcessingException e) {
            return false;
        }
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
            if ("nudge".equals(msg.action())) {
                return handleNudgeAction(ev, msg, entity, userId);
            }
            if ("setup".equals(msg.action())) {
                return handleSetupAction(ev, msg, entity, userId);
            }
            if (!userId.equals(entity.getCurrentPlayerId())) {
                return websocketError(ev, 400, PlayErrorMessages.NOT_YOUR_TURN);
            }

            final Card revealedCard = revealedSelectionCard(msg, entity, userId);
            GameSession session = SessionMapper.fromEntity(entity);
            PlayResult result = msg.selections() == null || msg.selections().isEmpty()
                    ? session.playCards(msg.cards())
                    : session.playSelections(msg.selections());
            if (result == PlayResult.INVALID) {
                return websocketError(ev, 400,
                        PlayErrorMessages.forReason(session.getLastInvalidReason(), session.getLastRequiredPileValue()));
            }

            GameSessionEntity updated = session.toEntity();
            SessionMapper.carryEloState(entity, updated);
            sessionRepo.save(updated);
            if (session.isFinished() && !entity.isEloUpdated()) {
                Map<String, EloChangeEntity> eloChanges = updateElo(session);
                if (!eloChanges.isEmpty()) {
                    updated.setEloUpdated(true);
                    updated.setEloChanges(eloChanges);
                    sessionRepo.save(updated);
                }
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

    /**
     * Relays a nudge (the farting sound) to every connection of the game. Nothing is stored or logged.
     */
    private APIGatewayProxyResponseEvent handleNudgeAction(
            APIGatewayV2WebSocketEvent event, PlayMessage message, GameSessionEntity entity, String userId) {
        Map<String, Object> nudge = NudgeMessage.build(entity.getPlayers(), userId, System.currentTimeMillis());
        postToGameConnections(message.sessionId(), websocketEndpoint(event), recipient -> nudge);
        return new APIGatewayProxyResponseEvent().withStatusCode(200);
    }

    /**
     * Applies a setup swap or ready action. Swaps prefer the multi-card lists and fall back to the single indexes
     * so that older clients keep working.
     */
    private boolean applySetupAction(GameSession session, PlayMessage message, String userId) {
        if ("swap".equals(message.setupAction()) && message.handIndices() != null && message.faceUpIndices() != null) {
            return session.swapStartingCards(userId, message.handIndices(), message.faceUpIndices());
        }
        if ("swap".equals(message.setupAction()) && message.handIndex() != null && message.faceUpIndex() != null) {
            return session.swapStartingCards(userId, message.handIndex(), message.faceUpIndex());
        }
        if ("starter".equals(message.setupAction())) {
            return session.setStarter(userId, message.starterId());
        }
        return "ready".equals(message.setupAction()) && session.markReady(userId);
    }

    private APIGatewayProxyResponseEvent handleSetupAction(
            APIGatewayV2WebSocketEvent event, PlayMessage message, GameSessionEntity entity, String userId) {
        if ("announce".equals(message.setupAction())) {
            return handleStartAnnouncement(event, message, entity, userId);
        }

        GameSession session = SessionMapper.fromEntity(entity);
        if (!applySetupAction(session, message, userId)) {
            return websocketError(event, 400, "That setup action is no longer available.");
        }
        GameSessionEntity updated = session.toEntity();
        updated.setStarting(entity.isStarting());
        SessionMapper.carryEloState(entity, updated);
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
                return websocketError(ev, 400, PlayErrorMessages.NOT_YOUR_TURN);
            }

            GameSession session = SessionMapper.fromEntity(entity);
            PlayResult result = session.pickupPile();
            if (result == PlayResult.INVALID) {
                return websocketError(ev, 400,
                        PlayErrorMessages.forReason(session.getLastInvalidReason(), session.getLastRequiredPileValue()));
            }

            GameSessionEntity updated = session.toEntity();
            SessionMapper.carryEloState(entity, updated);
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
            List<LeaderboardEntry> entries = sessionEntries(
                    playerIds, profiles, entity.getEloChanges(), entity.isFinished(), entity.getShitheadId());
            try {
                return corsResponse(200, mapper.writeValueAsString(entries));
            } catch (JsonProcessingException e) {
                log.error("leaderboardSession serialization failed", e);
                return corsResponse(500);
            }
        };
    }

    /**
     * Session leaderboard rows in seat order. {@code eloScore} is the current rating; {@code eloBefore} and
     * {@code eloAfter} come from the game's recorded Elo change and stay null when none was recorded. {@code shithead}
     * is true only for the seat whose id is {@code shitheadId} in a finished game, and false for every row otherwise.
     */
    public static List<LeaderboardEntry> sessionEntries(
            List<String> playerIds, Map<String, UserProfile> profiles, Map<String, EloChangeEntity> eloChanges,
            boolean finished, String shitheadId) {
        return playerIds.stream()
                .map(playerId -> {
                    UserProfile profile = profiles.getOrDefault(playerId, UserProfile.builder()
                            .userId(playerId)
                            .username("Unknown")
                            .eloScore(0)
                            .build());
                    EloChangeEntity change = eloChanges == null ? null : eloChanges.get(playerId);
                    return LeaderboardEntry.builder()
                            .userId(profile.getUserId())
                            .username(profile.getUsername())
                            .eloScore(profile.getEloScore())
                            .eloBefore(change == null ? null : change.getBefore())
                            .eloAfter(change == null ? null : change.getAfter())
                            .shithead(finished && shitheadId != null && shitheadId.equals(playerId))
                            .build();
                })
                .collect(Collectors.toList());
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

    /**
     * Applies the Elo update for a finished game and returns each player's before/after rating. Returns an empty
     * map when nothing was updated (fewer than two profiles, or a DynamoDB failure); the caller then leaves
     * {@code eloUpdated} unset so the update can be retried.
     */
    private Map<String, EloChangeEntity> updateElo(GameSession session) {
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
                return Map.of();
            }
            Map<String, EloService.EloChange> changes = EloService.calculateChanges(current, results);
            changes.forEach((id, change) -> {
                UserProfile userProfile = userRepo.get(id);
                if (userProfile != null) {
                    userProfile.setEloScore(change.after());
                    userRepo.save(userProfile);
                }
            });
            Map<String, EloChangeEntity> recorded = new HashMap<>();
            changes.forEach((id, change) -> recorded.put(id, EloChangeEntity.builder()
                    .before(change.before())
                    .after(change.after())
                    .build()));
            return recorded;
        } catch (SdkException e) {
            log.error("Elo update failed for session {}", session.getSessionId(), e);
            return Map.of();
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
                .decksCount(entity.getConfig() == null ? 1 : entity.getConfig().getDecksCount())
                .allowMixedHandAndFaceUpWhenDeckEmpty(entity.getConfig() != null
                        && entity.getConfig().isAllowMixedHandAndFaceUpWhenDeckEmpty())
                .allowFailedFaceUpPlay(entity.getConfig() != null
                        && entity.getConfig().isAllowFailedFaceUpPlay())
                .voiceEnabled(entity.getConfig() != null
                        && entity.getConfig().isVoiceEnabled())
                .revealedCard(revealedCard)
                .discardCount(discard.size())
                .discardPile(SessionMapper.entitiesToCardList(discard))
                .players(playerViews)
                .events(SessionMapper.entitiesToEvents(entity.getEvents()))
                .build();
    }
}
