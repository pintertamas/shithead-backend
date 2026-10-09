package com.tamaspinter.backend.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Builds API Gateway proxy responses with the same CORS headers as the account management routes.
 */
@Slf4j
public final class JsonResponses {

    static final Map<String, String> CORS_HEADERS = Map.of(
            "Access-Control-Allow-Origin", "*",
            "Access-Control-Allow-Headers", "Content-Type,Authorization",
            "Access-Control-Allow-Methods", "POST,GET,PUT,OPTIONS"
    );

    private JsonResponses() {
    }

    public static APIGatewayProxyResponseEvent text(int statusCode, String body) {
        return new APIGatewayProxyResponseEvent().withStatusCode(statusCode).withHeaders(CORS_HEADERS).withBody(body);
    }

    public static APIGatewayProxyResponseEvent json(ObjectMapper mapper, int statusCode, Object body) {
        try {
            return text(statusCode, mapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            log.error("Response serialization failed", e);
            return new APIGatewayProxyResponseEvent().withStatusCode(500).withHeaders(CORS_HEADERS);
        }
    }
}
