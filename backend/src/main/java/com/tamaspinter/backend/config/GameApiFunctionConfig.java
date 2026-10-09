package com.tamaspinter.backend.config;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Single entry point of the game Lambda ({@code SPRING_CLOUD_FUNCTION_DEFINITION=gameApi}).
 *
 * <p>The raw event is inspected for its shape and delegated to the matching existing handler
 * through {@link ApiRoutes}. WebSocket events carry {@code requestContext.routeKey} or
 * {@code eventType}; REST proxy events carry {@code httpMethod}. Unknown routes get a 404 with
 * CORS headers.
 */
@Configuration
public class GameApiFunctionConfig {

    private static final Map<String, String> CORS_HEADERS = Map.of(
            "Access-Control-Allow-Origin", "*",
            "Access-Control-Allow-Headers", "Content-Type,Authorization",
            "Access-Control-Allow-Methods", "POST,GET,PUT,OPTIONS"
    );

    private final ObjectMapper eventMapper;
    private final ApiRoutes routes;

    public GameApiFunctionConfig(ObjectMapper mapper, ApiRoutes routes) {
        this.eventMapper = mapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.routes = routes;
    }

    @Bean
    public Function<Map<String, Object>, Object> gameApi() {
        return this::dispatch;
    }

    /**
     * Routes a raw REST or WebSocket event; also used by {@code LambdaHandler}.
     */
    public Object dispatch(Map<String, Object> rawEvent) {
        if (isWebSocketEvent(rawEvent)) {
            return dispatchWebSocket(rawEvent);
        }
        if (rawEvent.get("httpMethod") != null) {
            return dispatchRest(rawEvent);
        }
        return notFound();
    }

    private Object dispatchRest(Map<String, Object> rawEvent) {
        APIGatewayProxyRequestEvent request = eventMapper.convertValue(rawEvent, APIGatewayProxyRequestEvent.class);
        String path = request.getPath() != null ? request.getPath() : request.getResource();
        Optional<ApiRoutes.RestRoute> route = routes.findRest(request.getHttpMethod(), path);
        if (route.isEmpty()) {
            return notFound();
        }
        if (request.getPathParameters() == null || request.getPathParameters().isEmpty()) {
            request.setPathParameters(route.get().pathParameters(path).orElse(Map.of()));
        }
        return route.get().handler().apply(request);
    }

    private Object dispatchWebSocket(Map<String, Object> rawEvent) {
        APIGatewayV2WebSocketEvent event = eventMapper.convertValue(rawEvent, APIGatewayV2WebSocketEvent.class);
        return routes.findWebSocket(routeKeyOf(event))
                .<Object>map(handler -> handler.apply(event))
                .orElseGet(GameApiFunctionConfig::notFound);
    }

    /**
     * The route key, or the {@code action} of the body when the route key is missing.
     */
    private String routeKeyOf(APIGatewayV2WebSocketEvent event) {
        String routeKey = event.getRequestContext() == null ? null : event.getRequestContext().getRouteKey();
        if (routeKey != null && !routeKey.isEmpty()) {
            return routeKey;
        }
        return actionOf(event.getBody());
    }

    private String actionOf(String body) {
        if (body == null) {
            return null;
        }
        try {
            JsonNode root = eventMapper.readTree(body);
            JsonNode action = root == null ? null : root.get("action");
            return action == null ? null : action.asText();
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static boolean isWebSocketEvent(Map<String, Object> rawEvent) {
        return rawEvent.get("requestContext") instanceof Map<?, ?> context
                && (context.get("routeKey") != null || context.get("eventType") != null);
    }

    static APIGatewayProxyResponseEvent notFound() {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(404)
                .withHeaders(CORS_HEADERS)
                .withBody("{\"message\":\"Not found\"}");
    }
}
