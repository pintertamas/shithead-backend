package com.tamaspinter.backend.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamaspinter.backend.service.AdminUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * HTTP mapping for the admin user list and block/unblock routes. The caller checks the game-admin group.
 */
@Component
@RequiredArgsConstructor
public class AdminUserHandler {

    private final AdminUserService adminUsers;
    private final ObjectMapper mapper;

    public APIGatewayProxyResponseEvent listUsers() {
        return JsonResponses.json(mapper, 200, adminUsers.listUsers());
    }

    public APIGatewayProxyResponseEvent setBlocked(String adminId, String targetId, boolean blocked) {
        AdminUserService.BlockResult result = adminUsers.setBlocked(adminId, targetId, blocked);
        return switch (result.outcome()) {
            case UPDATED -> JsonResponses.json(mapper, 200, Map.of(
                    "userId", targetId,
                    "blocked", blocked,
                    "closedConnections", result.closedConnections(),
                    "removedFromLobbies", result.removedFromLobbies()));
            case SELF_BLOCK_REFUSED -> JsonResponses.text(400, "{\"message\":\"You cannot block your own account.\"}");
            case NOT_FOUND -> JsonResponses.text(404, "{\"message\":\"User not found.\"}");
        };
    }
}
