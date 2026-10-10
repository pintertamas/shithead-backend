package com.tamaspinter.backend.config;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamaspinter.backend.bot.BotType;
import com.tamaspinter.backend.entity.EloChangeEntity;
import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.entity.PlayerEntity;
import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.mapper.SessionMapper;
import com.tamaspinter.backend.model.Player;
import com.tamaspinter.backend.model.UserProfile;
import com.tamaspinter.backend.repository.GameSessionRepository;
import com.tamaspinter.backend.repository.UserProfileRepository;
import com.tamaspinter.backend.service.BlockedUserGuard;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GameFunctionConfigBotsTest {

    private static final String SESSION_ID = "ABC123";
    private static final String OWNER = "owner-1";
    private static final String HUMAN = "p1";
    private static final String BOT = "bot-1";
    private static final String ADD_BEGINNER = "{\"action\":\"add\",\"botType\":\"BEGINNER\"}";
    private static final String FULL_MESSAGE =
            "{\"message\":\"Game is full: one deck seats 5 players. The owner can add a second deck.\"}";
    private static final int ONE_DECK_SEATS = 5;

    private final ObjectMapper mapper = new ObjectMapper();
    private GameSessionRepository sessionRepo;
    private UserProfileRepository userRepo;
    private BlockedUserGuard blockedUserGuard;
    private GameFunctionConfig config;
    private Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> manageBots;
    private Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> startGame;
    private Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> getState;

    @BeforeAll
    static void configureRegion() {
        // The config builds a DynamoDB client eagerly; the SDK only needs a region to construct it.
        System.setProperty("aws.region", "eu-central-1");
    }

    @BeforeEach
    void setUp() {
        sessionRepo = mock(GameSessionRepository.class);
        userRepo = mock(UserProfileRepository.class);
        blockedUserGuard = mock(BlockedUserGuard.class);
        config = new GameFunctionConfig(sessionRepo, userRepo, blockedUserGuard, mapper);
        manageBots = config.manageBots();
        startGame = config.startGame();
        getState = config.getState();
    }

    @Test
    void route_postGamesBots_isRegistered() {
        // Given
        ApiRoutes routes = new ApiRoutes(config, mock(AccountManagementFunctionConfig.class),
                mock(VoiceFunctionConfig.class));

        // When / Then
        assertTrue(routes.findRest("POST", "/prod/games/ABC/bots").isPresent());
        assertFalse(routes.findRest("GET", "/prod/games/ABC/bots").isPresent());
    }

    @Test
    void manageBots_add_returnsSeatAndSavesBotTypeWithNextNumber() throws JsonProcessingException {
        // Given: a lobby with just the owner
        when(sessionRepo.get(SESSION_ID)).thenReturn(lobby(1));

        // When
        APIGatewayProxyResponseEvent first = manageBots.apply(request(OWNER, ADD_BEGINNER));
        ArgumentCaptor<GameSessionEntity> saved = ArgumentCaptor.forClass(GameSessionEntity.class);
        verify(sessionRepo).save(saved.capture());
        when(sessionRepo.get(SESSION_ID)).thenReturn(saved.getValue());
        APIGatewayProxyResponseEvent second = manageBots.apply(request(OWNER, ADD_BEGINNER));

        // Then
        assertEquals(200, first.getStatusCode());
        JsonNode firstBody = mapper.readTree(first.getBody());
        assertEquals("Beginner Bot 1", firstBody.get("username").asText());
        assertTrue(firstBody.get("playerId").asText().startsWith(GameSession.BOT_ID_PREFIX));
        assertEquals("BEGINNER", saved.getValue().getPlayers().get(1).getBotType());
        assertEquals(200, second.getStatusCode());
        assertEquals("Beginner Bot 2", mapper.readTree(second.getBody()).get("username").asText());
    }

    @Test
    void manageBots_addUpToCapacity_fillsEverySeatThenRefusesWithMessage() {
        // Given: an owner alone in a one-deck lobby
        when(sessionRepo.get(SESSION_ID)).thenReturn(lobby(1));
        ArgumentCaptor<GameSessionEntity> saved = ArgumentCaptor.forClass(GameSessionEntity.class);

        // When: bots are added, each against the previously saved state
        for (int i = 1; i < ONE_DECK_SEATS; i++) {
            assertEquals(200, manageBots.apply(request(OWNER, ADD_BEGINNER)).getStatusCode());
            verify(sessionRepo, atLeastOnce()).save(saved.capture());
            when(sessionRepo.get(SESSION_ID)).thenReturn(saved.getValue());
        }
        APIGatewayProxyResponseEvent overflow = manageBots.apply(request(OWNER, ADD_BEGINNER));

        // Then
        assertEquals(ONE_DECK_SEATS, saved.getValue().getPlayers().size());
        assertEquals(409, overflow.getStatusCode());
        assertEquals(FULL_MESSAGE, overflow.getBody());
    }

    @Test
    void manageBots_removeBot_returnsOkAndDropsTheSeat() {
        // Given: a lobby with the owner and one bot
        GameSessionEntity lobby = lobbyWithBot();
        when(sessionRepo.get(SESSION_ID)).thenReturn(lobby);
        String botId = lobby.getPlayers().get(1).getPlayerId();

        // When
        APIGatewayProxyResponseEvent response = manageBots.apply(request(OWNER,
                "{\"action\":\"remove\",\"botId\":\"" + botId + "\"}"));

        // Then
        assertEquals(200, response.getStatusCode());
        ArgumentCaptor<GameSessionEntity> saved = ArgumentCaptor.forClass(GameSessionEntity.class);
        verify(sessionRepo).save(saved.capture());
        assertEquals(List.of(OWNER), saved.getValue().getPlayers().stream().map(PlayerEntity::getPlayerId).toList());
    }

    @Test
    void manageBots_removeHumanOrUnknownId_isNotFound() {
        // Given: a lobby with the owner, a human and a bot
        when(sessionRepo.get(SESSION_ID)).thenReturn(lobby(3));

        // When
        APIGatewayProxyResponseEvent human = manageBots.apply(request(OWNER,
                "{\"action\":\"remove\",\"botId\":\"" + HUMAN + "\"}"));
        APIGatewayProxyResponseEvent unknown = manageBots.apply(request(OWNER,
                "{\"action\":\"remove\",\"botId\":\"bot-nope\"}"));
        APIGatewayProxyResponseEvent missing = manageBots.apply(request(OWNER, "{\"action\":\"remove\"}"));

        // Then
        assertEquals(404, human.getStatusCode());
        assertEquals(404, unknown.getStatusCode());
        assertEquals(404, missing.getStatusCode());
        verify(sessionRepo, never()).save(any(GameSessionEntity.class));
    }

    @Test
    void manageBots_nonOwner_isForbiddenForAddAndRemove() {
        // Given
        when(sessionRepo.get(SESSION_ID)).thenReturn(lobbyWithBot());

        // When
        APIGatewayProxyResponseEvent add = manageBots.apply(request(HUMAN, ADD_BEGINNER));
        APIGatewayProxyResponseEvent remove = manageBots.apply(request(HUMAN,
                "{\"action\":\"remove\",\"botId\":\"" + BOT + "\"}"));

        // Then
        assertEquals(403, add.getStatusCode());
        assertEquals(403, remove.getStatusCode());
        verify(sessionRepo, never()).save(any(GameSessionEntity.class));
    }

    @Test
    void manageBots_blockedOwner_isForbiddenWithBlockedBody() {
        // Given
        when(sessionRepo.get(SESSION_ID)).thenReturn(lobby(1));
        when(blockedUserGuard.isBlocked(OWNER)).thenReturn(true);

        // When
        APIGatewayProxyResponseEvent response = manageBots.apply(request(OWNER, ADD_BEGINNER));

        // Then
        assertEquals(403, response.getStatusCode());
        assertEquals(BlockedUserGuard.BLOCKED_BODY, response.getBody());
        verify(sessionRepo, never()).save(any(GameSessionEntity.class));
    }

    @Test
    void manageBots_startedOrStartingGame_isConflict() {
        // Given: one started game and one lobby the owner is starting
        GameSessionEntity started = lobby(2);
        started.setStarted(true);
        GameSessionEntity starting = lobby(2);
        starting.setStarting(true);

        // When
        when(sessionRepo.get(SESSION_ID)).thenReturn(started);
        APIGatewayProxyResponseEvent whenStarted = manageBots.apply(request(OWNER, ADD_BEGINNER));
        when(sessionRepo.get(SESSION_ID)).thenReturn(starting);
        APIGatewayProxyResponseEvent whenStarting = manageBots.apply(request(OWNER, ADD_BEGINNER));

        // Then
        assertEquals(409, whenStarted.getStatusCode());
        assertEquals(409, whenStarting.getStatusCode());
        verify(sessionRepo, never()).save(any(GameSessionEntity.class));
    }

    @Test
    void manageBots_badBodyUnknownActionOrUnknownType_isBadRequest() {
        // Given
        when(sessionRepo.get(SESSION_ID)).thenReturn(lobby(1));

        // When / Then
        assertEquals(400, manageBots.apply(request(OWNER, "not json")).getStatusCode());
        assertEquals(400, manageBots.apply(request(OWNER, null)).getStatusCode());
        assertEquals(400, manageBots.apply(request(OWNER, "[1]")).getStatusCode());
        assertEquals(400, manageBots.apply(request(OWNER, "{\"action\":\"explode\"}")).getStatusCode());
        assertEquals(400, manageBots.apply(request(OWNER, "{\"botType\":\"BEGINNER\"}")).getStatusCode());
        assertEquals(400,
                manageBots.apply(request(OWNER, "{\"action\":\"add\",\"botType\":\"GENIUS\"}")).getStatusCode());
        assertEquals(400, manageBots.apply(request(OWNER, "{\"action\":\"add\"}")).getStatusCode());
        verify(sessionRepo, never()).save(any(GameSessionEntity.class));
    }

    @Test
    void manageBots_unknownSession_isNotFound() {
        // Given
        when(sessionRepo.get(SESSION_ID)).thenReturn(null);

        // When
        APIGatewayProxyResponseEvent response = manageBots.apply(request(OWNER, ADD_BEGINNER));

        // Then
        assertEquals(404, response.getStatusCode());
    }

    @Test
    void startGame_withBots_botsAreReadyHumansAreNotAndSavedOnce() {
        // Given: owner, one human and two bots
        when(sessionRepo.get(SESSION_ID)).thenReturn(lobby(4));

        // When
        APIGatewayProxyResponseEvent response = startGame.apply(startRequest(OWNER));

        // Then
        assertEquals(200, response.getStatusCode());
        ArgumentCaptor<GameSessionEntity> saved = ArgumentCaptor.forClass(GameSessionEntity.class);
        verify(sessionRepo).save(saved.capture());
        GameSessionEntity entity = saved.getValue();
        assertTrue(entity.isStarted());
        assertFalse(entity.isSetupComplete());
        assertEquals(4, entity.getPlayers().size());
        for (PlayerEntity player : entity.getPlayers()) {
            assertEquals(player.getBotType() != null, player.isReady(), player.getPlayerId());
        }
    }

    @Test
    void getState_withBot_exposesIsBotAndBotTypeForTheBotOnly() throws JsonProcessingException {
        // Given
        when(sessionRepo.get(SESSION_ID)).thenReturn(lobbyWithBot());

        // When
        JsonNode state = mapper.readTree(getState.apply(stateRequest(OWNER)).getBody());

        // Then: Jackson keeps the record component names "isBot" and "isYou" as they are
        JsonNode owner = state.get("players").get(0);
        JsonNode bot = state.get("players").get(1);
        assertTrue(owner.get("isYou").asBoolean());
        assertFalse(owner.get("isBot").asBoolean());
        assertTrue(owner.get("botType").isNull());
        assertTrue(bot.get("isBot").asBoolean());
        assertEquals("BEGINNER", bot.get("botType").asText());
        assertFalse(bot.get("isYou").asBoolean());
    }

    @Test
    void updateElo_botIsShithead_skipsEverythingAndTouchesNoProfile() {
        // Given: a finished game where the bot lost
        GameSession session = finishedGame(BOT);

        // When
        Map<String, EloChangeEntity> changes = config.updateElo(session);

        // Then
        assertTrue(changes.isEmpty());
        verifyNoInteractions(userRepo);
    }

    @Test
    void updateElo_humanIsShithead_ratesOnlyHumans() {
        // Given: a finished game where the owner lost, with a bot at the table
        GameSession session = finishedGame(OWNER);
        when(userRepo.batchGet(anyList())).thenReturn(List.of(profile(OWNER), profile(HUMAN)));
        when(userRepo.get(OWNER)).thenReturn(profile(OWNER));
        when(userRepo.get(HUMAN)).thenReturn(profile(HUMAN));

        // When
        Map<String, EloChangeEntity> changes = config.updateElo(session);

        // Then
        assertEquals(2, changes.size());
        assertTrue(changes.keySet().containsAll(List.of(OWNER, HUMAN)));
        assertNull(changes.get(BOT));
        assertTrue(changes.get(OWNER).getAfter() < changes.get(OWNER).getBefore());
        assertTrue(changes.get(HUMAN).getAfter() > changes.get(HUMAN).getBefore());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
        verify(userRepo).batchGet(ids.capture());
        assertEquals(List.of(OWNER, HUMAN), ids.getValue());
    }

    @Test
    void leaderboardSession_botSeat_isShownUnderItsSeatNameWithoutProfileLookup() throws JsonProcessingException {
        // Given: the owner and a bot
        when(sessionRepo.get(SESSION_ID)).thenReturn(lobbyWithBot());
        when(userRepo.batchGet(List.of(OWNER))).thenReturn(List.of(profile(OWNER)));

        // When
        JsonNode rows = mapper.readTree(config.leaderboardSession().apply(stateRequest(OWNER)).getBody());

        // Then
        assertEquals(2, rows.size());
        assertEquals("Beginner Bot 1", rows.get(1).get("username").asText());
        assertEquals(GameSession.DEFAULT_RATING, rows.get(1).get("eloScore").asDouble(), 0.001);
    }

    private static UserProfile profile(String userId) {
        return UserProfile.builder().userId(userId).username(userId).eloScore(1000).build();
    }

    /** A finished game: owner, one human and one bot, with the given shithead. */
    private static GameSession finishedGame(String shitheadId) {
        GameSession session = GameSession.builder().sessionId(SESSION_ID).ownerId(OWNER).build();
        session.addPlayer(OWNER, OWNER);
        session.addPlayer(HUMAN, HUMAN);
        session.getPlayers().add(Player.builder().playerId(BOT).username("Beginner Bot 1")
                .botType(BotType.BEGINNER).build());
        session.setStarted(true);
        session.setFinished(true);
        session.setShitheadId(shitheadId);
        return session;
    }

    /** An unstarted one-deck lobby: the owner, then a human, then bots for the remaining seats. */
    private static GameSessionEntity lobby(int players) {
        GameSession session = GameSession.builder()
                .sessionId(SESSION_ID)
                .ownerId(OWNER)
                .config(oneDeckConfig())
                .build();
        session.addPlayer(OWNER, "owner");
        if (players >= 2) {
            session.addPlayer(HUMAN, "human");
        }
        for (int i = 2; i < players; i++) {
            session.addBot(BotType.BEGINNER);
        }
        return SessionMapper.toEntity(session);
    }

    private static GameSessionEntity lobbyWithBot() {
        GameSession session = GameSession.builder()
                .sessionId(SESSION_ID)
                .ownerId(OWNER)
                .config(oneDeckConfig())
                .build();
        session.addPlayer(OWNER, "owner");
        session.addBot(BotType.BEGINNER);
        return SessionMapper.toEntity(session);
    }

    private static GameConfig oneDeckConfig() {
        return GameConfig.builder().decksCount(1).faceDownCount(3).faceUpCount(3).handCount(3).burnCount(4).build();
    }

    private static APIGatewayProxyRequestEvent request(String sub, String body) {
        return new APIGatewayProxyRequestEvent()
                .withHttpMethod("POST")
                .withPathParameters(Map.of("sessionId", SESSION_ID))
                .withBody(body)
                .withRequestContext(context(sub));
    }

    private static APIGatewayProxyRequestEvent startRequest(String sub) {
        return new APIGatewayProxyRequestEvent()
                .withHttpMethod("POST")
                .withBody("{\"sessionId\":\"" + SESSION_ID + "\"}")
                .withRequestContext(context(sub));
    }

    private static APIGatewayProxyRequestEvent stateRequest(String sub) {
        return new APIGatewayProxyRequestEvent()
                .withHttpMethod("GET")
                .withPathParameters(Map.of("sessionId", SESSION_ID))
                .withRequestContext(context(sub));
    }

    private static APIGatewayProxyRequestEvent.ProxyRequestContext context(String sub) {
        Map<String, Object> claims = Map.of("sub", sub);
        APIGatewayProxyRequestEvent.ProxyRequestContext context = new APIGatewayProxyRequestEvent.ProxyRequestContext();
        context.setAuthorizer(Map.of("claims", claims));
        return context;
    }
}
