package com.tamaspinter.backend.config;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.mapper.SessionMapper;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.model.Suit;
import com.tamaspinter.backend.model.UserProfile;
import com.tamaspinter.backend.model.websocket.GameEnded;
import com.tamaspinter.backend.model.websocket.PlayMessage;
import com.tamaspinter.backend.service.EloService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.*;
import java.util.stream.Collectors;

import com.tamaspinter.backend.game.GameManager;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.repository.*;

@Slf4j
@Configuration
public class GameFunctionConfig {

    private final GameManager gameManager;
    private final GameSessionRepository sessionRepo;
    private final UserProfileRepository userRepo;
    private final ObjectMapper mapper;

    public GameFunctionConfig(GameManager gameManager,
                              GameSessionRepository sessionRepo,
                              UserProfileRepository userRepo,
                              ObjectMapper mapper) {
        this.gameManager = gameManager;
        this.sessionRepo = sessionRepo;
        this.userRepo = userRepo;
        this.mapper = mapper;
    }

    @Bean
    public Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> createGame() {
        return req -> {
            String sessionId = UUID.randomUUID().toString();
            log.info("Creating game session with ID: {}", sessionId);
            GameSession session = gameManager.createSession(sessionId);
            sessionRepo.save(SessionMapper.toEntity(session));
            Map<String, String> body = Map.of("sessionId", sessionId);
            try {
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(200)
                        .withBody(mapper.writeValueAsString(body));
            } catch (Exception e) {
                log.error("createGame failed", e);
                return new APIGatewayProxyResponseEvent().withStatusCode(500);
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
            String sessionId = (String) data.get("sessionId");
            String playerId = (String) data.get("playerId");
            String username = (String) data.get("username");
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
            String sessionId = (String) data.get("sessionId");
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
            Map data;
            try {
                data = mapper.readValue(req.getBody(), Map.class);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
            String sessionId = (String) data.get("sessionId");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cardsJson = (List) data.get("cards");
            List<Card> cards = new ArrayList<>();
            for (var c : cardsJson) {
                cards.add(new Card(
                        (Suit) c.get("suit"),
                        (int) c.get("value"),
                        CardRule.valueOf((String) c.get("rule")),
                        (boolean) c.get("alwaysPlayable")
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
            Map<String, Double> current = userRepo.batchGet(payload.getPlayerIds())
                    .stream().collect(Collectors.toMap(UserProfile::getUserId, UserProfile::getEloScore));
            Map<String, Double> updated = EloService.updateRatings(current, payload.getResults());
            updated.forEach((id, elo) -> {
                var u = userRepo.get(id);
                u.setEloScore(elo);
                userRepo.save(u);
            });
        };
    }
}
