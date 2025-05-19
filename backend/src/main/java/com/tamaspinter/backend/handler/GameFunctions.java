package com.tamaspinter.backend.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.model.Suit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Function;
import java.util.*;

import com.tamaspinter.backend.game.GameManager;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.repository.*;

@Configuration
public class GameFunctions {
    private final GameManager gameManager = new GameManager();
    private final GameSessionRepository sessionRepo = new GameSessionRepository();
    private final UserProfileRepository userRepo = new UserProfileRepository();
    private final ObjectMapper mapper = new ObjectMapper();

    @Bean
    public Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> createGame() {
        return req -> {
            try {
                String sessionId = UUID.randomUUID().toString();
                GameSession session = gameManager.createSession(sessionId);
                sessionRepo.save(session);
                Map<String, String> body = Map.of("sessionId", sessionId);
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(200)
                        .withBody(mapper.writeValueAsString(body));
            } catch (Exception e) {
                return new APIGatewayProxyResponseEvent().withStatusCode(500);
            }
        };
    }

    @Bean
    public Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> joinGame() {
        return req -> {
            try {
                Map<String, String> data = mapper.readValue(req.getBody(), Map.class);
                String sessionId = data.get("sessionId");
                String playerId = data.get("playerId");
                String username = data.get("username");
                GameSession session = gameManager.getSession(sessionId);
                session.addPlayer(playerId, username);
                sessionRepo.save(session);
                return new APIGatewayProxyResponseEvent().withStatusCode(200);
            } catch (Exception e) {
                return new APIGatewayProxyResponseEvent().withStatusCode(400);
            }
        };
    }

    @Bean
    public Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> startGame() {
        return req -> {
            try {
                Map<String, String> data = mapper.readValue(req.getBody(), Map.class);
                String sessionId = data.get("sessionId");
                GameSession session = gameManager.getSession(sessionId);
                session.start();
                sessionRepo.save(session);
                return new APIGatewayProxyResponseEvent().withStatusCode(200);
            } catch (Exception e) {
                return new APIGatewayProxyResponseEvent().withStatusCode(400);
            }
        };
    }

    @Bean
    public Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> getState() {
        return req -> {
            String sessionId = req.getPathParameters().get("sessionId");
            GameSession session = gameManager.getSession(sessionId);
            try {
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(200)
                        .withBody(mapper.writeValueAsString(session));
            } catch (Exception e) {
                return new APIGatewayProxyResponseEvent().withStatusCode(500);
            }
        };
    }

    @Bean
    public Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> playCard() {
        return req -> {
            try {
                Map<String, Object> data = mapper.readValue(req.getBody(), Map.class);
                String sessionId = (String) data.get("sessionId");
                List<Map<String, Object>> cardsJson = (List) data.get("cards");
                List<Card> cards = new ArrayList<>();
                for (Map<String, Object> c : cardsJson) {
                    cards.add(new Card(
                            (Suit) c.get("suit"),
                            (int) c.get("value"),
                            CardRule.valueOf((String) c.get("rule")),
                            (boolean) c.get("alwaysPlayable")
                    ));
                }
                GameSession session = gameManager.getSession(sessionId);
                boolean ok = session.playCards(cards);
                sessionRepo.save(session);
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(ok ? 200 : 400);
            } catch (Exception e) {
                return new APIGatewayProxyResponseEvent().withStatusCode(500);
            }
        };
    }
}
