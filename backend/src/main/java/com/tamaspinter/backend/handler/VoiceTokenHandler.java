package com.tamaspinter.backend.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.entity.PlayerEntity;
import com.tamaspinter.backend.repository.GameSessionRepository;
import com.tamaspinter.backend.service.BlockedUserGuard;
import com.tamaspinter.backend.service.LiveKitAccessTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP mapping for {@code POST /games/{sessionId}/voice-token}.
 *
 * <p>Answers 404 for an unknown game, 403 when the caller is not a player or voice is off for the game,
 * and 503 when the LiveKit settings are empty. Otherwise returns {@code {url, token, room}}.
 */
@Component
@RequiredArgsConstructor
public class VoiceTokenHandler {

    static final String NOT_CONFIGURED_BODY = "{\"message\":\"Voice chat is not configured\"}";

    private final GameSessionRepository sessionRepo;
    private final BlockedUserGuard blockedUserGuard;
    private final LiveKitAccessTokenService tokens;
    private final ObjectMapper mapper;

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
        if (!tokens.isConfigured()) {
            return JsonResponses.text(503, NOT_CONFIGURED_BODY);
        }
        String token = tokens.createToken(sessionId, userId, player.get().getUsername());
        return JsonResponses.json(mapper, 200, Map.of(
                "url", tokens.serverUrl(),
                "token", token,
                "room", sessionId));
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
