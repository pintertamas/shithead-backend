package com.tamaspinter.backend.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.entity.PlayerEntity;
import com.tamaspinter.backend.repository.GameSessionRepository;
import com.tamaspinter.backend.service.BlockedUserGuard;
import com.tamaspinter.backend.service.LiveKitAccessTokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP mapping for {@code POST /games/{sessionId}/voice-token}.
 *
 * <p>Answers 404 for an unknown game, 403 when the caller is not a player or voice is off for the game,
 * and 503 when the LiveKit settings are empty. Before a token is minted it reads this month's voice usage
 * (users item {@code __voice_usage#YYYY-MM}, UTC) and answers 503 once it reaches the free LiveKit allowance,
 * or when that usage cannot be read (fail closed). Otherwise returns {@code {url, token, room}}.
 */
@Slf4j
@Component
public class VoiceTokenHandler {

    static final String NOT_CONFIGURED_BODY = "{\"message\":\"Voice chat is not configured\"}";
    static final String PAUSED_BODY = "{\"message\":\"Voice chat is paused until next month to stay within the free LiveKit allowance.\"}";
    static final String UNAVAILABLE_BODY = "{\"message\":\"Voice chat is unavailable right now.\"}";

    private static final double MONTHLY_LIMIT_MINUTES = 5000;
    private static final String USAGE_KEY_PREFIX = "__voice_usage#";
    private static final String USER_KEY = "user_id";
    private static final String USAGE_MINUTES = "minutes";

    private final GameSessionRepository sessionRepo;
    private final BlockedUserGuard blockedUserGuard;
    private final LiveKitAccessTokenService tokens;
    private final ObjectMapper mapper;
    private final DynamoDbClient dynamoClient;
    private final String usersTable;

    // Explicit constructor: Lombok does not copy @Value onto the generated constructor parameter,
    // so the table name would not be injected (same pattern as UserConnectionService).
    public VoiceTokenHandler(
            GameSessionRepository sessionRepo,
            BlockedUserGuard blockedUserGuard,
            LiveKitAccessTokenService tokens,
            ObjectMapper mapper,
            DynamoDbClient dynamoClient,
            @Value("${dynamodb.users.table}") String usersTable) {
        this.sessionRepo = sessionRepo;
        this.blockedUserGuard = blockedUserGuard;
        this.tokens = tokens;
        this.mapper = mapper;
        this.dynamoClient = dynamoClient;
        this.usersTable = usersTable;
    }

    public APIGatewayProxyResponseEvent issueToken(APIGatewayProxyRequestEvent req) {
        String userId = subjectOf(req);
        if (userId == null) {
            return JsonResponses.text(401, "{\"message\":\"Unauthorized\"}");
        }
        if (blockedUserGuard.isBlocked(userId)) {
            return JsonResponses.text(403, BlockedUserGuard.BLOCKED_BODY);
        }
        String sessionId = pathParameter(req, "sessionId");
        GameSessionEntity game = sessionId == null ? null : sessionRepo.get(sessionId);
        if (game == null) {
            return JsonResponses.text(404, "{\"message\":\"Game not found.\"}");
        }
        Optional<PlayerEntity> player = findPlayer(game, userId);
        if (player.isEmpty()) {
            return JsonResponses.text(403, "{\"message\":\"Only players in this game can join voice chat.\"}");
        }
        if (game.getConfig() == null || !game.getConfig().isVoiceEnabled()) {
            return JsonResponses.text(403, "{\"message\":\"Voice chat is not enabled for this game.\"}");
        }
        Optional<APIGatewayProxyResponseEvent> unavailable = unavailableResponse();
        if (unavailable.isPresent()) {
            return unavailable.get();
        }
        String token = tokens.createToken(sessionId, userId, player.get().getUsername());
        return JsonResponses.json(mapper, 200, Map.of(
                "url", tokens.serverUrl(),
                "token", token,
                "room", sessionId));
    }

    /**
     * The 503 to answer instead of a token: LiveKit not configured, this month's allowance used up,
     * or the usage item could not be read. Empty when a token may be issued.
     */
    private Optional<APIGatewayProxyResponseEvent> unavailableResponse() {
        if (!tokens.isConfigured()) {
            return Optional.of(JsonResponses.text(503, NOT_CONFIGURED_BODY));
        }
        String usageKey = usageKey();
        try {
            if (minutesUsed(usageKey) >= MONTHLY_LIMIT_MINUTES) {
                return Optional.of(JsonResponses.text(503, PAUSED_BODY));
            }
        } catch (SdkException | IllegalStateException e) {
            log.error("Could not read voice usage item {}", usageKey, e);
            return Optional.of(JsonResponses.text(503, UNAVAILABLE_BODY));
        }
        return Optional.empty();
    }

    /**
     * Minutes recorded for the usage item; an absent item or attribute counts as zero.
     *
     * @throws IllegalStateException when the {@code minutes} attribute is present but not a number
     */
    private double minutesUsed(String usageKey) {
        AttributeValue minutes = dynamoClient.getItem(GetItemRequest.builder()
                        .tableName(usersTable)
                        .consistentRead(true)
                        .key(Map.of(USER_KEY, AttributeValue.fromS(usageKey)))
                        .build())
                .item()
                .get(USAGE_MINUTES);
        if (minutes == null) {
            return 0;
        }
        if (minutes.n() == null) {
            throw new IllegalStateException("Voice usage minutes attribute is not a number");
        }
        return Double.parseDouble(minutes.n());
    }

    private static String usageKey() {
        return USAGE_KEY_PREFIX + YearMonth.now(ZoneOffset.UTC);
    }

    private static Optional<PlayerEntity> findPlayer(GameSessionEntity game, String userId) {
        List<PlayerEntity> players = game.getPlayers();
        if (players == null) {
            return Optional.empty();
        }
        return players.stream().filter(player -> userId.equals(player.getPlayerId())).findFirst();
    }

    private static String subjectOf(APIGatewayProxyRequestEvent req) {
        if (req.getRequestContext() == null || req.getRequestContext().getAuthorizer() == null) {
            return null;
        }
        Object claims = req.getRequestContext().getAuthorizer().get("claims");
        if (claims instanceof Map<?, ?> claimValues && claimValues.get("sub") instanceof String sub && !sub.isBlank()) {
            return sub;
        }
        return null;
    }

    private static String pathParameter(APIGatewayProxyRequestEvent req, String name) {
        Map<String, String> parameters = req.getPathParameters();
        return parameters == null ? null : parameters.get(name);
    }
}
