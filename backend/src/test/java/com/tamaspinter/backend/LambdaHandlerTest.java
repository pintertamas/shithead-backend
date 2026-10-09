package com.tamaspinter.backend;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamaspinter.backend.config.AccountManagementFunctionConfig;
import com.tamaspinter.backend.config.ApiRoutes;
import com.tamaspinter.backend.config.GameApiFunctionConfig;
import com.tamaspinter.backend.config.GameFunctionConfig;
import com.tamaspinter.backend.config.VoiceFunctionConfig;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class LambdaHandlerTest {

    @Test
    void handleRequest_restEvent_writesTheHandlerResponseUnwrapped() throws IOException {
        // Given
        ObjectMapper mapper = new ObjectMapper();
        GameFunctionConfig game = Mockito.mock(GameFunctionConfig.class);
        AccountManagementFunctionConfig account = Mockito.mock(AccountManagementFunctionConfig.class);
        VoiceFunctionConfig voice = Mockito.mock(VoiceFunctionConfig.class);
        @SuppressWarnings("unchecked")
        Function<com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent,
                APIGatewayProxyResponseEvent> leaderboard = Mockito.mock(Function.class);
        when(leaderboard.apply(any())).thenReturn(new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withHeaders(Map.of("Access-Control-Allow-Origin", "*"))
                .withBody("[]"));
        when(game.leaderboardTop()).thenReturn(leaderboard);
        LambdaHandler handler = new LambdaHandler(new GameApiFunctionConfig(mapper, new ApiRoutes(game, account, voice)), mapper);
        String event = "{\"httpMethod\":\"GET\",\"path\":\"/leaderboard/top\",\"requestContext\":{}}";

        // When
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        handler.handleRequest(new ByteArrayInputStream(event.getBytes(StandardCharsets.UTF_8)), out, null);

        // Then
        JsonNode json = mapper.readTree(out.toByteArray());
        assertEquals(200, json.get("statusCode").asInt());
        assertEquals("*", json.get("headers").get("Access-Control-Allow-Origin").asText());
        assertEquals("[]", json.get("body").asText());
        assertTrue(json.get("body").isTextual());
    }
}
