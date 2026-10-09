package com.tamaspinter.backend.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamaspinter.backend.service.GameBrowseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * HTTP mapping for the lobby browser route.
 */
@Component
@RequiredArgsConstructor
public class GameBrowseHandler {

    private final GameBrowseService gameBrowse;
    private final ObjectMapper mapper;

    public APIGatewayProxyResponseEvent listOpenGames() {
        return JsonResponses.json(mapper, 200, gameBrowse.listOpenGames());
    }
}
