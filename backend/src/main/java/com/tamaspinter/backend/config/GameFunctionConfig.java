package com.tamaspinter.backend.config;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamaspinter.backend.bot.BotTurnRunner;
import com.tamaspinter.backend.bot.BotType;
import com.tamaspinter.backend.entity.EloChangeEntity;
import com.tamaspinter.backend.entity.GameConfigEntity;
import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.entity.PlayerEntity;
import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.game.GameEvent;
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
import com.tamaspinter.backend.service.GameEloService;
import com.tamaspinter.backend.service.RematchLobbies;
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
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Configuration
@RequiredArgsConstructor
@SuppressWarnings({"PMD.GodClass", "PMD.TooManyMethods", "PMD.ExcessiveImports", "PMD.CyclomaticComplexity"})
public class GameFunctionConfig {

    private static final Map<String, String> CORS_HEADERS = Map.of(
            "Access-Control-Allow-Origin", "*",
            "Access-Control-Allow-Headers", "Content-Type,Authorization",
            "Access-Control-Allow-Methods", "POST,GET,PUT,OPTIONS"
    );
    /** Deck count of a game that raised its deck, and the burn count the glue sets for two decks too. */
    private static final int TWO_DECKS = 2;
    private static final int TWO_DECKS_BURN_COUNT = 6;
    private static final int REMATCH_ATTEMPTS = 3;
    private static final String REMATCH_STARTED_BODY = "{\"error\":\"Rematch already started\"}";

    private final GameSessionRepository sessionRepo;
    private final UserProfileRepository userRepo;
    private final BlockedUserGuard blockedUserGuard;
    private final ObjectMapper mapper;
    private final DynamoDbClient dynamoClient = DynamoDbClient.create();
    private final String wsConnectionsTable = System.getenv("WS_CONNECTIONS_TABLE");
    /** Endpoint of the WebSocket API, used to broadcast from the REST bot-turn route (no request context there). */
    private final String wsManagementEndpoint = System.getenv("WS_MANAGEMENT_ENDPOINT");

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
                BotTurnRunner.completeSetup(session);
            } catch (IllegalStateException e) {
                if (entity.isStarting()) {
                    entity.setStarting(false);
                    sessionRepo.save(entity);
                }
                return conflictResponse(e.getMessage());
            }
            sessionRepo.save(session.toEntity());
            markRematchStarted(session);
            return corsResponse(200);
        };
    }

    /** Best effort: flags the finished game that this started game is the rematch of. Never fails the start. */
    private void markRematchStarted(GameSession started) {
        if (started.getRematchOf() == null) {
            return;
        }
        try {
            GameSessionEntity previous = sessionRepo.get(started.getRematchOf());
            if (previous != null && started.getSessionId().equals(previous.getRematchSessionId())) {
                previous.setRematchStarted(true);
                sessionRepo.save(previous);
            }
        } catch (SdkException e) {
            log.warn("Could not mark rematch of game {} as started", started.getRematchOf(), e);
        }
    }

    /**
     * Play again: {@code POST /games/{sessionId}/rematch}. The first human player of a finished game creates an
     * unstarted lobby with the same settings and bots and gets {@code {"sessionId","created":true}}; later callers
     * join that lobby like /join-game and get {@code created:false}. 409 while the game is not finished or once the
     * rematch lobby has started; 403 for anyone who did not play.
     */
    @Bean
    @SuppressWarnings("PMD.CognitiveComplexity")
    public Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> rematch() {
        return req -> {
            String userId = subjectOf(req);
            if (userId == null) {
                return corsResponse(401, "{\"message\":\"Unauthorized\"}");
            }
            if (blockedUserGuard.isBlocked(userId)) {
                return corsResponse(403, BlockedUserGuard.BLOCKED_BODY);
            }
            String sessionId = req.getPathParameters() == null ? null : req.getPathParameters().get("sessionId");
            GameSessionEntity finished = sessionId == null ? null : sessionRepo.get(sessionId);
            if (finished == null) {
                return corsResponse(404);
            }
            if (!isSeatedHuman(finished, userId)) {
                return corsResponse(403, "{\"message\":\"Only players of this game can start a rematch.\"}");
            }
            if (!finished.isFinished()) {
                return corsResponse(409, "{\"error\":\"Game has not finished\"}");
            }
            for (int attempt = 0; attempt < REMATCH_ATTEMPTS; attempt++) {
                if (finished.isRematchStarted()) {
                    return corsResponse(409, REMATCH_STARTED_BODY);
                }
                GameSessionEntity lobby = finished.getRematchSessionId() == null
                        ? null : sessionRepo.get(finished.getRematchSessionId());
                if (lobby != null) {
                    return joinRematchLobby(lobby, userId);
                }
                String created = createRematchLobby(finished, userId);
                if (created != null) {
                    return corsResponse(200, rematchBody(created, true));
                }
                finished = sessionRepo.get(sessionId);
                if (finished == null) {
                    return corsResponse(404);
                }
            }
            return conflictResponse("Could not start the rematch, please try again");
        };
    }

    private static String rematchBody(String sessionId, boolean created) {
        return "{\"sessionId\":\"" + sessionId + "\",\"created\":" + created + "}";
    }

    private static String subjectOf(APIGatewayProxyRequestEvent req) {
        if (req.getRequestContext() == null || req.getRequestContext().getAuthorizer() == null) {
            return null;
        }
        Object claims = req.getRequestContext().getAuthorizer().get("claims");
        if (claims instanceof Map<?, ?> values && values.get("sub") instanceof String sub && !sub.isBlank()) {
            return sub;
        }
        return null;
    }

    /** Joins an existing rematch lobby exactly like the join-game route (idempotent, capacity 409). */
    private APIGatewayProxyResponseEvent joinRematchLobby(GameSessionEntity lobby, String userId) {
        if (lobby.isStarted()) {
            return corsResponse(409, REMATCH_STARTED_BODY);
        }
        String lobbyId = lobby.getSessionId();
        if (lobby.getPlayers() != null
                && lobby.getPlayers().stream().anyMatch(player -> userId.equals(player.getPlayerId()))) {
            return corsResponse(200, rematchBody(lobbyId, false));
        }
        cleanupOldSessions(userId, lobbyId);
        UserProfile user = userRepo.get(userId);
        GameSession session = SessionMapper.fromEntity(lobby);
        try {
            session.addPlayer(userId, user != null ? user.getUsername() : "Unknown");
        } catch (IllegalStateException e) {
            return conflictResponse(e.getMessage());
        }
        sessionRepo.save(session.toEntity());
        return corsResponse(200, rematchBody(lobbyId, false));
    }

    /**
     * Writes a new lobby and then claims the finished game's pointer to it with a conditional write. Returns the
     * new lobby id, or null (after deleting the lobby again) when another caller claimed the pointer first.
     */
    private String createRematchLobby(GameSessionEntity finished, String userId) {
        UserProfile user = userRepo.get(userId);
        String newId = RematchLobbies.newCode(code -> sessionRepo.get(code) != null);
        GameSession lobby = RematchLobbies.newLobby(newId, finished, userId, user != null ? user.getUsername() : "Unknown");
        sessionRepo.save(lobby.toEntity());
        if (!sessionRepo.claimRematch(finished, finished.getRematchSessionId(), newId)) {
            sessionRepo.delete(newId);
            return null;
        }
        cleanupOldSessions(userId, newId);
        return newId;
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
     * Owner-only, before the start: seats or removes a bot. {@code POST /games/{sessionId}/bots} with body
     * {@code {"action":"add","botType":"BEGINNER"}} returns the new seat as {@code {"playerId","username"}};
     * {@code {"action":"remove","botId":"..."}} removes that bot. Seats are limited like human joins (409).
     */
    @Bean
    @SuppressWarnings("PMD.CognitiveComplexity")
    public Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> manageBots() {
        return req -> {
            String sessionId = req.getPathParameters() == null ? null : req.getPathParameters().get("sessionId");
            GameSessionEntity entity = sessionId == null ? null : sessionRepo.get(sessionId);
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
                return corsResponse(403, "{\"message\":\"Only the game owner can add or remove bots.\"}");
            }
            if (entity.isStarted() || entity.isStarting()) {
                return conflictResponse("Game already started");
            }
            JsonNode body = readJson(req.getBody());
            String action = body == null ? null : body.path("action").asText(null);
            GameSession session = SessionMapper.fromEntity(entity);
            if ("add".equals(action)) {
                BotType type = BotType.fromName(body.path("botType").asText(null));
                if (type == null) {
                    return corsResponse(400, "{\"message\":\"Unknown bot type.\"}");
                }
                Player bot;
                try {
                    bot = session.addBot(type);
                } catch (IllegalStateException e) {
                    return conflictResponse(e.getMessage());
                }
                sessionRepo.save(session.toEntity());
                return corsResponse(200, writeJson(Map.of("playerId", bot.getPlayerId(), "username", bot.getUsername())));
            }
            if ("remove".equals(action)) {
                if (!session.removeBot(body.path("botId").asText(null))) {
                    return corsResponse(404, "{\"message\":\"That bot is not in this game.\"}");
                }
                sessionRepo.save(session.toEntity());
                return corsResponse(200);
            }
            return corsResponse(400);
        };
    }

    /**
     * Client-paced bot turn: {@code POST /games/{sessionId}/bot-turn} with {@code {"expectedPlayerId":"<bot id>",
     * "expectedMoves":<seq of the newest event>}}. Plays exactly one bot move, and only when a human is still in play,
     * that bot is to move and the newest event still has the expected seq; otherwise the state is returned unchanged,
     * so a repeated or late request never moves twice. Only seated humans may call it (403).
     */
    @Bean
    @SuppressWarnings("PMD.CognitiveComplexity")
    public Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> botTurn() {
        return req -> {
            String sessionId = req.getPathParameters() == null ? null : req.getPathParameters().get("sessionId");
            GameSessionEntity entity = sessionId == null ? null : sessionRepo.get(sessionId);
            if (entity == null) {
                return corsResponse(404);
            }
            @SuppressWarnings("unchecked")
            Map<String, String> claims = (Map<String, String>) req.getRequestContext().getAuthorizer().get("claims");
            String userId = claims.get("sub");
            if (blockedUserGuard.isBlocked(userId)) {
                return corsResponse(403, BlockedUserGuard.BLOCKED_BODY);
            }
            if (!isSeatedHuman(entity, userId)) {
                return corsResponse(403, "{\"message\":\"Join this game before sending actions.\"}");
            }
            JsonNode body = readJson(req.getBody());
            if (body == null) {
                return corsResponse(400);
            }
            GameSession session = SessionMapper.fromEntity(entity);
            if (!isExpectedBotMove(session, body) || !BotTurnRunner.playOneBotMove(session)) {
                return stateResponse(entity, userId);
            }
            GameSessionEntity updated = saveFinishedGame(entity, session);
            broadcastState(sessionId, updated, wsManagementEndpoint);
            return stateResponse(updated, userId);
        };
    }

    private static boolean isSeatedHuman(GameSessionEntity entity, String userId) {
        return userId != null && entity.getPlayers() != null && entity.getPlayers().stream()
                .anyMatch(player -> userId.equals(player.getPlayerId()) && player.getBotType() == null);
    }

    /** True when a human is still in play and the expected bot is to move at the expected point of the game. */
    private static boolean isExpectedBotMove(GameSession session, JsonNode body) {
        String expectedPlayerId = body.path("expectedPlayerId").asText(null);
        long expectedMoves = body.path("expectedMoves").asLong(-1L);
        return session.hasHumanInPlay() && BotTurnRunner.isBotTurn(session)
                && Objects.equals(expectedPlayerId, session.getCurrentPlayerId())
                && lastEventSeq(session) == expectedMoves;
    }

    /** Seq of the newest activity-feed event; 0 while the game has no events. Changes with every move that records one. */
    private static long lastEventSeq(GameSession session) {
        List<GameEvent> events = session.getEvents();
        return events.isEmpty() ? 0L : events.get(events.size() - 1).seq();
    }

    private APIGatewayProxyResponseEvent stateResponse(GameSessionEntity entity, String userId) {
        try {
            return corsResponse(200, mapper.writeValueAsString(buildGameStateView(entity, userId)));
        } catch (JsonProcessingException e) {
            log.error("Game state serialization failed", e);
            return corsResponse(500);
        }
    }

    private JsonNode readJson(String body) {
        if (body == null) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(body);
            return root != null && root.isObject() ? root : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize response", e);
        }
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
            String endpoint = websocketEndpoint(ev);
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

            Card broadcastCard = result == PlayResult.PICKUP ? revealedCard : null;
            GameSessionEntity updated = saveAfterMove(entity, session, msg.sessionId(), endpoint, broadcastCard);
            broadcastState(msg.sessionId(), updated, endpoint, broadcastCard);

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

        String endpoint = websocketEndpoint(event);
        GameSession session = SessionMapper.fromEntity(entity);
        if (!applySetupAction(session, message, userId)) {
            return websocketError(event, 400, "That setup action is no longer available.");
        }
        GameSessionEntity updated = saveAfterMove(entity, session, message.sessionId(), endpoint, null);
        broadcastState(message.sessionId(), updated, endpoint);
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

    // package-private for tests: the user comes from a DynamoDB connection row, which tests cannot reach
    String websocketUserId(APIGatewayV2WebSocketEvent event) {
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
            if (userId == null) {
                return websocketError(ev, 400, PlayErrorMessages.NOT_YOUR_TURN);
            }
            String endpoint = websocketEndpoint(ev);
            if (!userId.equals(entity.getCurrentPlayerId())) {
                return websocketError(ev, 400, PlayErrorMessages.NOT_YOUR_TURN);
            }

            GameSession session = SessionMapper.fromEntity(entity);
            PlayResult result = session.pickupPile();
            if (result == PlayResult.INVALID) {
                return websocketError(ev, 400,
                        PlayErrorMessages.forReason(session.getLastInvalidReason(), session.getLastRequiredPileValue()));
            }

            GameSessionEntity updated = saveAfterMove(entity, session, msg.sessionId(), endpoint, null);
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
            Map<String, UserProfile> profiles = entity.getPlayers() == null
                    ? new HashMap<>()
                    : new GameEloService(userRepo).sessionProfiles(entity.getPlayers());
            List<String> playerIds = entity.getPlayers() == null
                    ? List.of()
                    : entity.getPlayers().stream().map(PlayerEntity::getPlayerId).collect(Collectors.toList());
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
                            .bot(Boolean.TRUE.equals(profile.getBot()) ? Boolean.TRUE : null)
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
                            .bot(Boolean.TRUE.equals(profile.getBot()) ? Boolean.TRUE : null)
                            .games(profile.getGames())
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
     * Saves the state of a player's play, pickup or setup and lets the bots take their turns. When a bot is to move
     * next, the player's own move is saved and broadcast on its own first; the bots then play, and each bot move is
     * saved and broadcast. The session is saved once more at the end and, when the game just finished, the Elo update
     * is recorded. Keeps the stored starting flag and Elo bookkeeping.
     */
    // package-private for tests
    GameSessionEntity saveAfterMove(GameSessionEntity stored, GameSession session, String gameSessionId,
                                    String endpoint, Card revealedCard) {
        if (session.hasHumanInPlay()) {
            return saveFinishedGame(stored, session);
        }
        return playBotTurnsAndSave(stored, session, gameSessionId, endpoint, revealedCard);
    }

    private GameSessionEntity playBotTurnsAndSave(GameSessionEntity stored, GameSession session, String gameSessionId,
                                                  String endpoint, Card revealedCard) {
        BotTurnRunner.playBotTurns(session, botMoveListener(stored, gameSessionId, endpoint, revealedCard));
        return saveFinishedGame(stored, session);
    }

    /** Saves the session as it now stands and records the Elo update once, when the game has just finished. */
    private GameSessionEntity saveFinishedGame(GameSessionEntity stored, GameSession session) {
        GameSessionEntity updated = storedEntity(stored, session);
        sessionRepo.save(updated);
        if (session.isFinished() && !stored.isEloUpdated()) {
            Map<String, EloChangeEntity> eloChanges = updateElo(session);
            if (!eloChanges.isEmpty()) {
                updated.setEloUpdated(true);
                updated.setEloChanges(eloChanges);
                sessionRepo.save(updated);
            }
        }
        return updated;
    }

    /** Saves and broadcasts the session after one bot move. The handler's final broadcast carries the same reveal. */
    private BotTurnRunner.BotMoveListener botMoveListener(GameSessionEntity stored, String gameSessionId,
                                                          String endpoint, Card revealedCard) {
        return botSession -> {
            GameSessionEntity state = storedEntity(stored, botSession);
            sessionRepo.save(state);
            broadcastState(gameSessionId, state, endpoint, revealedCard);
        };
    }

    /** The session as it is stored: keeps the stored starting flag and Elo bookkeeping. */
    private static GameSessionEntity storedEntity(GameSessionEntity stored, GameSession session) {
        GameSessionEntity entity = session.toEntity();
        entity.setStarting(stored.isStarting());
        SessionMapper.carryEloState(stored, entity);
        return entity;
    }

    /**
     * Applies the Elo update for a finished game (see {@link GameEloService#update}) and returns each seat's
     * before/after rating, or an empty map when nothing was updated so {@code eloUpdated} stays unset and it retries.
     */
    // package-private for tests
    Map<String, EloChangeEntity> updateElo(GameSession session) {
        return new GameEloService(userRepo).update(session);
    }

    private void broadcastState(String gameSessionId, GameSessionEntity state, String endpoint) {
        broadcastState(gameSessionId, state, endpoint, null);
    }

    // package-private for tests
    void broadcastState(String gameSessionId, GameSessionEntity state, String endpoint, Card revealedCard) {
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
                : entity.getPlayers().stream().filter(player -> player.getBotType() == null)
                        .map(PlayerEntity::getPlayerId).toList();
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
                            .isBot(player.getBotType() != null)
                            .botType(player.getBotType())
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
                .finishedAt(entity.getFinishedAt())
                .rematchSessionId(entity.getRematchSessionId())
                .rematchStarted(entity.isRematchStarted())
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
