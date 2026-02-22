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
import com.tamaspinter.backend.model.UserProfile;
import com.tamaspinter.backend.model.api.GameStateView;
import com.tamaspinter.backend.model.api.LeaderboardEntry;
import com.tamaspinter.backend.model.api.PlayerStateView;
import com.tamaspinter.backend.model.websocket.PickupMessage;
import com.tamaspinter.backend.model.websocket.PlayMessage;
import com.tamaspinter.backend.repository.GameSessionRepository;
import com.tamaspinter.backend.repository.UserProfileRepository;
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
public class GameFunctionConfig {

    private static final Map<String, String> CORS_HEADERS = Map.of(
            "Access-Control-Allow-Origin", "*",
            "Access-Control-Allow-Headers", "Content-Type,Authorization",
            "Access-Control-Allow-Methods", "POST,GET,OPTIONS"
    );

    private final GameSessionRepository sessionRepo;
    private final UserProfileRepository userRepo;
    private final ObjectMapper mapper;
    private final DynamoDbClient dynamoClient = DynamoDbClient.create();
    private final String wsConnectionsTable = System.getenv("WS_CONNECTIONS_TABLE");

    private static APIGatewayProxyResponseEvent corsResponse(int statusCode) {
        return new APIGatewayProxyResponseEvent().withStatusCode(statusCode).withHeaders(CORS_HEADERS);
    }

    private static APIGatewayProxyResponseEvent corsResponse(int statusCode, String body) {
        return new APIGatewayProxyResponseEvent().withStatusCode(statusCode).withHeaders(CORS_HEADERS).withBody(body);
    }

    @Bean
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
            if (entity.getPlayers() != null
                    && entity.getPlayers().stream().anyMatch(player -> userId.equals(player.getPlayerId()))) {
                return corsResponse(409);
            }

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
            if (!userId.equals(entity.getOwnerId())) {
                return corsResponse(403);
            }

            GameSession session = SessionMapper.fromEntity(entity);
            if (session.getPlayers().size() < 2) {
                return corsResponse(400);
            }
            try {
                session.start();
            } catch (IllegalStateException e) {
                return corsResponse(409);
            }
            sessionRepo.save(session.toEntity());
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
    public Function<APIGatewayV2WebSocketEvent, APIGatewayProxyResponseEvent> playCardWS() {
        return ev -> {
            PlayMessage msg;
            try {
                msg = mapper.readValue(ev.getBody(), PlayMessage.class);
            } catch (JsonProcessingException e) {
                return new APIGatewayProxyResponseEvent().withStatusCode(400);
            }

            GameSessionEntity entity = sessionRepo.get(msg.sessionId());
            if (entity == null) {
                return new APIGatewayProxyResponseEvent().withStatusCode(404);
            }

            GameSession session = SessionMapper.fromEntity(entity);
            PlayResult result = session.playCards(msg.cards());
            if (result == PlayResult.INVALID) {
                return new APIGatewayProxyResponseEvent().withStatusCode(400);
            }

            GameSessionEntity updated = session.toEntity();
            sessionRepo.save(updated);
            String endpoint = "https://" + ev.getRequestContext().getDomainName()
                    + "/" + ev.getRequestContext().getStage();
            broadcastState(msg.sessionId(), updated, endpoint);

            if (session.isFinished()) {
                updateElo(session);
            }

            return new APIGatewayProxyResponseEvent().withStatusCode(200);
        };
    }

    @Bean
    public Function<APIGatewayV2WebSocketEvent, APIGatewayProxyResponseEvent> pickupPileWS() {
        return ev -> {
            PickupMessage msg;
            try {
                msg = mapper.readValue(ev.getBody(), PickupMessage.class);
            } catch (JsonProcessingException e) {
                return new APIGatewayProxyResponseEvent().withStatusCode(400);
            }

            GameSessionEntity entity = sessionRepo.get(msg.sessionId());
            if (entity == null) {
                return new APIGatewayProxyResponseEvent().withStatusCode(404);
            }

            GameSession session = SessionMapper.fromEntity(entity);
            PlayResult result = session.pickupPile();
            if (result == PlayResult.INVALID) {
                return new APIGatewayProxyResponseEvent().withStatusCode(400);
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

    private void updateElo(GameSession session) {
        String shitheadId = session.getShitheadId();
        Map<String, Double> results = new HashMap<>();
        for (var player : session.getPlayers()) {
            results.put(player.getPlayerId(), player.getPlayerId().equals(shitheadId) ? 0.0 : 1.0);
        }
        List<String> playerIds = session.getPlayers().stream()
                .map(Player::getPlayerId)
                .collect(Collectors.toList());
        try {
            Map<String, Double> current = userRepo.batchGet(playerIds)
                    .stream().collect(Collectors.toMap(UserProfile::getUserId, UserProfile::getEloScore));
            Map<String, Double> updated = EloService.updateRatings(current, results);
            updated.forEach((id, elo) -> {
                UserProfile userProfile = userRepo.get(id);
                userProfile.setEloScore(elo);
                userRepo.save(userProfile);
            });
        } catch (SdkException e) {
            log.error("Elo update failed for session {}", session.getSessionId(), e);
        }
    }

    private void broadcastState(String gameSessionId, GameSessionEntity state, String endpoint) {
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
                    GameStateView view = buildGameStateView(state, userId);
                    apigwClient.postToConnection(PostToConnectionRequest.builder()
                            .connectionId(connectionId)
                            .data(SdkBytes.fromByteArray(mapper.writeValueAsBytes(view)))
                            .build());
                } catch (JsonProcessingException e) {
                    log.error("Failed to serialize game state for broadcast", e);
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

    private GameStateView buildGameStateView(GameSessionEntity entity, String viewerId) {
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
                .finished(entity.isFinished())
                .currentPlayerId(entity.getCurrentPlayerId())
                .shitheadId(entity.getShitheadId())
                .isOwner(viewerId != null && viewerId.equals(entity.getOwnerId()))
                .deckCount(deck.size())
                .discardCount(discard.size())
                .discardPile(SessionMapper.entitiesToCardList(discard))
                .players(playerViews)
                .build();
    }
}
