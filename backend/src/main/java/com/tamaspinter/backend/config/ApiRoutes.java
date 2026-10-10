package com.tamaspinter.backend.config;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The route table of the single game API Lambda ({@code gameApi}).
 *
 * <p>To expose a new route, add one line to the constructor:
 * <ul>
 *   <li>REST: {@code rest("GET", "/games/{gameId}", someFunctionConfig.someHandler());}</li>
 *   <li>WebSocket: {@code websocket("chat", game.playCardWS());} (the route key, which is the
 *       {@code action} the client sends).</li>
 * </ul>
 * The handlers are the existing {@code Function} beans, so route logic stays where it is.
 * Path templates use {@code {name}} segments; they are matched against the end of the request path.
 */
@Component
public class ApiRoutes {

    private final List<RestRoute> restRoutes = new ArrayList<>();
    private final Map<String, Function<APIGatewayV2WebSocketEvent, APIGatewayProxyResponseEvent>> webSocketRoutes =
            new HashMap<>();

    public ApiRoutes(GameFunctionConfig game, AccountManagementFunctionConfig account, VoiceFunctionConfig voice) {
        rest("POST", "/join-game", game.joinGame());
        rest("POST", "/leave-game", game.leaveGame());
        rest("POST", "/start-game", game.startGame());
        rest("GET", "/state/{sessionId}", game.getState());
        rest("GET", "/leaderboard/top", game.leaderboardTop());
        rest("GET", "/leaderboard/session/{sessionId}", game.leaderboardSession());
        rest("GET", "/profile", account.accountManagement());
        rest("PUT", "/profile", account.accountManagement());
        rest("DELETE", "/profile", account.accountManagement());
        rest("POST", "/admin/doomsday", account.accountManagement());
        rest("GET", "/admin/users", account.accountManagement());
        rest("POST", "/admin/users/{userId}/block", account.accountManagement());
        rest("POST", "/admin/users/{userId}/unblock", account.accountManagement());
        rest("GET", "/games", account.accountManagement());
        rest("POST", "/games/{sessionId}/voice-token", voice.voiceToken());

        websocket("play", game.playCardWS());
        websocket("setup", game.playCardWS());
        websocket("chat", game.playCardWS());
        websocket("nudge", game.playCardWS());
        websocket("pickup", game.pickupPileWS());
    }

    private void rest(String method, String pattern,
                      Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> handler) {
        restRoutes.add(new RestRoute(method, pattern, handler));
    }

    private void websocket(String routeKey, Function<APIGatewayV2WebSocketEvent, APIGatewayProxyResponseEvent> handler) {
        webSocketRoutes.put(routeKey, handler);
    }

    public Optional<RestRoute> findRest(String method, String path) {
        return restRoutes.stream().filter(route -> route.matches(method, path)).findFirst();
    }

    public Optional<Function<APIGatewayV2WebSocketEvent, APIGatewayProxyResponseEvent>> findWebSocket(String routeKey) {
        return Optional.ofNullable(webSocketRoutes.get(routeKey));
    }

    /** One REST route: HTTP method, path template and the handler that serves it. */
    public record RestRoute(String method, String pattern,
                            Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> handler) {

        public boolean matches(String requestMethod, String path) {
            return method.equalsIgnoreCase(requestMethod) && pathParameters(path).isPresent();
        }

        /**
         * Values of the {@code {name}} segments in path, or empty when path does not fit the template.
         */
        public Optional<Map<String, String>> pathParameters(String path) {
            List<String> wanted = segments(pattern);
            List<String> actual = segments(path);
            if (actual.size() < wanted.size()) {
                return Optional.empty();
            }
            List<String> tail = actual.subList(actual.size() - wanted.size(), actual.size());
            Map<String, String> values = new HashMap<>();
            for (int i = 0; i < wanted.size(); i++) {
                String expected = wanted.get(i);
                if (isParameter(expected)) {
                    values.put(expected.substring(1, expected.length() - 1), tail.get(i));
                } else if (!expected.equals(tail.get(i))) {
                    return Optional.empty();
                }
            }
            return Optional.of(values);
        }

        private static boolean isParameter(String segment) {
            return segment.startsWith("{") && segment.endsWith("}");
        }

        private static List<String> segments(String path) {
            if (path == null) {
                return List.of();
            }
            return Arrays.stream(path.split("/")).filter(segment -> !segment.isEmpty()).toList();
        }
    }
}
