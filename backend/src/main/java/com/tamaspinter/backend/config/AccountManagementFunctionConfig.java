package com.tamaspinter.backend.config;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.model.UserProfile;
import com.tamaspinter.backend.repository.GameSessionRepository;
import com.tamaspinter.backend.repository.UserProfileRepository;
import com.tamaspinter.backend.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiClient;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.DeleteConnectionRequest;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.GoneException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class AccountManagementFunctionConfig {

    private static final Map<String, String> CORS_HEADERS = Map.of(
            "Access-Control-Allow-Origin", "*",
            "Access-Control-Allow-Headers", "Content-Type,Authorization",
            "Access-Control-Allow-Methods", "POST,GET,PUT,OPTIONS"
    );

    private final GameSessionRepository sessionRepo;
    private final UserProfileRepository userRepo;
    private final UserProfileService profileService;
    private final ObjectMapper mapper;
    private final DynamoDbClient dynamoClient = DynamoDbClient.create();
    private final String wsConnectionsTable = System.getenv("WS_CONNECTIONS_TABLE");
    private final String gameSessionsTable = System.getenv("GAME_SESSIONS_TABLE");
    private final String wsManagementEndpoint = System.getenv("WS_MANAGEMENT_ENDPOINT");

    private static APIGatewayProxyResponseEvent corsResponse(int statusCode) {
        return new APIGatewayProxyResponseEvent().withStatusCode(statusCode).withHeaders(CORS_HEADERS);
    }

    private static APIGatewayProxyResponseEvent corsResponse(int statusCode, String body) {
        return new APIGatewayProxyResponseEvent().withStatusCode(statusCode).withHeaders(CORS_HEADERS).withBody(body);
    }

    @Bean
    public Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> accountManagement() {
        return req -> {
            String path = req.getPath() == null ? "" : req.getPath();
            String method = req.getHttpMethod();
            Map<String, Object> claims = requestClaims(req);
            if (path.endsWith("/profile") && "GET".equals(method)) {
                return readProfile(claims);
            }
            if (path.endsWith("/profile") && "PUT".equals(method)) {
                return updateProfile(req, claims);
            }
            if (path.endsWith("/admin/doomsday") && "POST".equals(method)) {
                return clearActiveGames(claims);
            }
            return corsResponse(404);
        };
    }

    private APIGatewayProxyResponseEvent readProfile(Map<String, Object> claims) {
        String userId = (String) claims.get("sub");
        UserProfile profile = profileService.getOrCreateProfile(userId, claims);
        Map<String, Object> result = Map.of(
                "username", profile.getUsername(),
                "canClearGames", hasAdminGroup(claims));
        try {
            return corsResponse(200, mapper.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            log.error("Profile serialization failed", e);
            return corsResponse(500);
        }
    }

    private APIGatewayProxyResponseEvent updateProfile(
            APIGatewayProxyRequestEvent req, Map<String, Object> claims) {
        Map<?, ?> body;
        try {
            body = mapper.readValue(req.getBody(), Map.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return corsResponse(400);
        }
        if (body == null) {
            return corsResponse(400);
        }
        Object requestedName = body.get("username");
        String username = requestedName instanceof String value ? value.trim() : "";
        if (!username.matches("[\\p{L}\\p{N}_ -]{2,24}")) {
            return corsResponse(400, "{\"message\":\"Choose a name between 2 and 24 letters, numbers, spaces, hyphens, or underscores.\"}");
        }
        UserProfile profile = profileService.getOrCreateProfile((String) claims.get("sub"), claims);
        if (!userRepo.updateUsernameIfAvailable(profile, username)) {
            return corsResponse(409, "{\"message\":\"That nickname is already taken. Please choose another.\"}");
        }
        renamePlayerInActiveGames(profile.getUserId(), username);
        return readProfile(claims);
    }

    private void renamePlayerInActiveGames(String userId, String username) {
        for (GameSessionEntity game : sessionRepo.findAll()) {
            if (game.getPlayers() == null) {
                continue;
            }
            game.getPlayers().stream()
                    .filter(player -> userId.equals(player.getPlayerId()))
                    .forEach(player -> player.setUsername(username));
            if (game.getPlayers().stream().anyMatch(player -> userId.equals(player.getPlayerId()))) {
                sessionRepo.save(game);
            }
        }
    }

    private APIGatewayProxyResponseEvent clearActiveGames(Map<String, Object> claims) {
        if (!hasAdminGroup(claims)) {
            return corsResponse(403, "{\"message\":\"Administrator access is required for this action.\"}");
        }
        ConnectionCleanupResult connections = closeAllConnections();
        int deletedGames = deleteAllGames();
        return cleanupResponse(deletedGames, connections);
    }

    private ConnectionCleanupResult closeAllConnections() {
        List<Map<String, AttributeValue>> connections = scanTable(wsConnectionsTable);
        int closedConnections = 0;
        int failedConnections = 0;
        try (ApiGatewayManagementApiClient client = ApiGatewayManagementApiClient.builder()
                .endpointOverride(URI.create(wsManagementEndpoint))
                .build()) {
            for (Map<String, AttributeValue> connection : connections) {
                String connectionId = connection.get("connection_id").s();
                try {
                    client.deleteConnection(DeleteConnectionRequest.builder()
                            .connectionId(connectionId)
                            .build());
                    closedConnections++;
                    deleteConnectionRecord(connectionId);
                } catch (GoneException e) {
                    deleteConnectionRecord(connectionId);
                } catch (SdkException e) {
                    failedConnections++;
                    log.warn("Could not close WebSocket connection {}", connectionId, e);
                }
            }
        }
        return new ConnectionCleanupResult(closedConnections, failedConnections);
    }

    private APIGatewayProxyResponseEvent cleanupResponse(
            int deletedGames, ConnectionCleanupResult connections) {
        try {
            return corsResponse(200, mapper.writeValueAsString(Map.of(
                    "deletedGames", deletedGames,
                    "closedConnections", connections.closed(),
                    "failedConnections", connections.failed())));
        } catch (JsonProcessingException e) {
            log.error("Doomsday response serialization failed", e);
            return corsResponse(500);
        }
    }

    private record ConnectionCleanupResult(int closed, int failed) { }

    private boolean hasAdminGroup(Map<String, Object> claims) {
        Object groups = claims.get("cognito:groups");
        if (groups instanceof List<?> groupList) {
            return groupList.contains("game-admin");
        }
        if (groups instanceof String groupText) {
            String normalized = groupText.replace("[", "").replace("]", "");
            return Arrays.stream(normalized.split(","))
                    .map(String::trim)
                    .anyMatch("game-admin"::equals);
        }
        return false;
    }

    private Map<String, Object> requestClaims(APIGatewayProxyRequestEvent req) {
        Map<String, Object> authorizer = req.getRequestContext().getAuthorizer();
        if (authorizer != null && authorizer.get("claims") instanceof Map<?, ?> claimValues) {
            Map<String, Object> result = new HashMap<>();
            claimValues.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return Map.of();
    }

    private int deleteAllGames() {
        List<Map<String, AttributeValue>> games = scanTable(gameSessionsTable);
        for (Map<String, AttributeValue> game : games) {
            dynamoClient.deleteItem(DeleteItemRequest.builder()
                    .tableName(gameSessionsTable)
                    .key(Map.of("game_id", game.get("game_id")))
                    .build());
        }
        return games.size();
    }

    private void deleteConnectionRecord(String connectionId) {
        dynamoClient.deleteItem(DeleteItemRequest.builder()
                .tableName(wsConnectionsTable)
                .key(Map.of("connection_id", AttributeValue.fromS(connectionId)))
                .build());
    }

    private List<Map<String, AttributeValue>> scanTable(String tableName) {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        Map<String, AttributeValue> lastKey = null;
        while (true) {
            ScanRequest.Builder request = ScanRequest.builder().tableName(tableName);
            if (lastKey != null && !lastKey.isEmpty()) {
                request.exclusiveStartKey(lastKey);
            }
            ScanResponse response = dynamoClient.scan(request.build());
            items.addAll(response.items());
            lastKey = response.lastEvaluatedKey();
            if (lastKey == null || lastKey.isEmpty()) {
                break;
            }
        }
        return items;
    }
}
