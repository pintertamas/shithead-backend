package com.tamaspinter.backend.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.model.Suit;
import com.tamaspinter.backend.model.UserProfile;
import com.tamaspinter.backend.service.EloService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.*;
import java.util.stream.Collectors;

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
            String sessionId = UUID.randomUUID().toString();
            GameSession session = gameManager.createSession(sessionId);
            sessionRepo.save(session.toEntity());
            Map<String,String> body = Map.of("sessionId", sessionId);
            try {
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(200)
                        .withBody(mapper.writeValueAsString(body));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        };
    }

    @Bean
    public Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> joinGame() {
        return req -> {
            Map data;
            try {
                data = mapper.readValue(req.getBody(), Map.class);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
            String sessionId = (String)data.get("sessionId");
            String playerId = (String)data.get("playerId");
            String username = (String)data.get("username");
            GameSession session = gameManager.getSession(sessionId);
            session.addPlayer(playerId, username);
            sessionRepo.save(session.toEntity());
            return new APIGatewayProxyResponseEvent().withStatusCode(200);
        };
    }

    @Bean
    public Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> startGame() {
        return req -> {
            Map data;
            try {
                data = mapper.readValue(req.getBody(), Map.class);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
            String sessionId = (String)data.get("sessionId");
            GameSession session = gameManager.getSession(sessionId);
            session.start();
            sessionRepo.save(session.toEntity());
            return new APIGatewayProxyResponseEvent().withStatusCode(200);
        };
    }

    @Bean
    public Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> getState() {
        return req -> {
            String sessionId = req.getPathParameters().get("sessionId");
            GameSessionEntity entity = sessionRepo.get(sessionId);
            try {
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(200)
                        .withBody(mapper.writeValueAsString(entity));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        };
    }

    @Bean
    public Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> playCard() {
        return req -> {
            Map data = null;
            try {
                data = mapper.readValue(req.getBody(), Map.class);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
            String sessionId = (String)data.get("sessionId");
            @SuppressWarnings("unchecked")
            List<Map<String,Object>> cardsJson = (List)data.get("cards");
            List<Card> cards = new ArrayList<>();
            for (var c : cardsJson) {
                cards.add(new Card(
                        (Suit)c.get("suit"),
                        (int)c.get("value"),
                        CardRule.valueOf((String)c.get("rule")),
                        (boolean)c.get("alwaysPlayable")
                ));
            }
            GameSession session = gameManager.getSession(sessionId);
            boolean ok = session.playCards(cards);
            sessionRepo.save(session.toEntity());
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(ok ? 200 : 400);
        };
    }

    @Bean
    public Function<APIGatewayV2WebSocketEvent, APIGatewayProxyResponseEvent> connect() {
        return ev -> {
            String connId = ev.getRequestContext().getConnectionId();
            String playerId = /* extract from JWT */ "";
            // register connection
            return new APIGatewayProxyResponseEvent().withStatusCode(200);
        };
    }

    @Bean
    public Function<APIGatewayV2WebSocketEvent, APIGatewayProxyResponseEvent> disconnect() {
        return ev -> {
            String connId = ev.getRequestContext().getConnectionId();
            // unregister connection
            return new APIGatewayProxyResponseEvent().withStatusCode(200);
        };
    }

    @Bean
    public Function<APIGatewayV2WebSocketEvent, Void> playCardWS() {
        return ev -> {
            PlayMessage msg;
            try {
                msg = mapper.readValue(ev.getBody(), PlayMessage.class);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
            GameSession session = gameManager.getSession(msg.getSessionId());
            session.playCards(msg.getCards());
            sessionRepo.save(session.toEntity());
            // broadcast updated state
            return null;
        };
    }

    @Bean
    public Consumer<SNSEvent> onGameEnded() {
        return snsEvent -> {
            String json = snsEvent.getRecords().get(0).getSNS().getMessage();
            GameEnded payload;
            try {
                payload = mapper.readValue(json, GameEnded.class);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
            Map<String,Double> current = userRepo.batchGet(payload.getPlayerIds())
                    .stream().collect(Collectors.toMap(UserProfile::getUserId, UserProfile::getEloScore));
            Map<String,Double> updated = EloService.updateRatings(current, payload.getResults());
            updated.forEach((id,elo) -> {
                var u = userRepo.get(id);
                u.setEloScore(elo);
                userRepo.save(u);
            });
        };
    }

    /** Message payload for WS playCard route */
    public static class PlayMessage {
        private String sessionId;
        private List<Card> cards;

        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public List<Card> getCards() { return cards; }
        public void setCards(List<Card> cards) { this.cards = cards; }
    }

    /** SNS payload when a game ends */
    public static class GameEnded {
        private List<String> playerIds;
        private Map<String, Double> results; // mapping playerId -> score (1 or 0)

        public List<String> getPlayerIds() { return playerIds; }
        public void setPlayerIds(List<String> playerIds) { this.playerIds = playerIds; }
        public Map<String, Double> getResults() { return results; }
        public void setResults(Map<String, Double> results) { this.results = results; }
    }
}