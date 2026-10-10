package com.tamaspinter.backend.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamaspinter.backend.service.AdminUserService;
import com.tamaspinter.backend.service.AdminUserService.AdminUserView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminUserHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void listUsers_storedEmail_isWrittenAsString() throws Exception {
        // Given
        JsonNode body = listUsersBody(new AdminUserView("u-1", "Ann", "user@example.test", 1000, false));

        // When / Then
        assertEquals("user@example.test", body.get(0).get("email").asText());
    }

    @Test
    void listUsers_noStoredEmail_isWrittenAsNull() throws Exception {
        // Given
        JsonNode body = listUsersBody(new AdminUserView("u-2", "Bob", null, 1000, false));

        // When / Then: the key is present with a null value
        assertTrue(body.get(0).has("email"));
        assertTrue(body.get(0).get("email").isNull());
    }

    private static JsonNode listUsersBody(AdminUserView... users) throws Exception {
        AdminUserService adminUsers = mock(AdminUserService.class);
        when(adminUsers.listUsers()).thenReturn(List.of(users));
        AdminUserHandler handler = new AdminUserHandler(adminUsers, MAPPER);

        APIGatewayProxyResponseEvent response = handler.listUsers();

        assertEquals(200, response.getStatusCode());
        return MAPPER.readTree(response.getBody());
    }
}
