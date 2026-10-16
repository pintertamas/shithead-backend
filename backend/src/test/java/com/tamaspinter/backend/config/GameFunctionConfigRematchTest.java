package com.tamaspinter.backend.config;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamaspinter.backend.bot.BotType;
import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.entity.PlayerEntity;
import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.mapper.SessionMapper;
import com.tamaspinter.backend.model.UserProfile;
import com.tamaspinter.backend.repository.GameSessionRepository;
import com.tamaspinter.backend.repository.UserProfileRepository;
import com.tamaspinter.backend.service.BlockedUserGuard;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameFunctionConfigRematchTest {

    private static final String OLD_ID = "OLD111";
    private static final String LOBBY_ID = "NEW222";
    private static final String OWNER = "owner-1";
    private static final String HUMAN = "p1";
    private static final String STRANGER = "stranger";

    private final ObjectMapper mapper = new ObjectMapper();
    private GameSessionRepository sessionRepo;
    private UserProfileRepository userRepo;
    private Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> rematch;
    private Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> startGame;

    @BeforeAll
    static void configureRegion() {
        // The config builds a DynamoDB client eagerly; the SDK only needs a region to construct it.
        System.setProperty("aws.region", "eu-central-1");
    }

    @BeforeEach
    void setUp() {
        sessionRepo = mock(GameSessionRepository.class);
        userRepo = mock(UserProfileRepository.class);
        GameFunctionConfig config = new GameFunctionConfig(sessionRepo, userRepo, mock(BlockedUserGuard.class), mapper);
        rematch = config.rematch();
        startGame = config.startGame();
    }

    @Test
    void route_postGamesRematch_isRegistered() {
        GameFunctionConfig config = new GameFunctionConfig(sessionRepo, userRepo, mock(BlockedUserGuard.class), mapper);
        ApiRoutes routes = new ApiRoutes(config, mock(AccountManagementFunctionConfig.class),
                mock(VoiceFunctionConfig.class));

        assertTrue(routes.findRest("POST", "/prod/games/ABC/rematch").isPresent());
        assertFalse(routes.findRest("GET", "/prod/games/ABC/rematch").isPresent());
    }

    @Test
    void rematch_firstCaller_createsLobbyWithSameConfigAndFreshBots() throws JsonProcessingException {
        // Given: a finished game with two humans and one bot, two decks
        GameSessionEntity finished = finishedGame(null);
        when(sessionRepo.get(OLD_ID)).thenReturn(finished);
        when(userRepo.get(OWNER)).thenReturn(UserProfile.builder().userId(OWNER).username("Owner").build());
        when(sessionRepo.claimRematch(eq(finished), isNull(), any())).thenReturn(true);

        // When
        APIGatewayProxyResponseEvent response = rematch.apply(request(OWNER));

        // Then: 200 with created true and a 6-char code
        assertEquals(200, response.getStatusCode());
        JsonNode body = mapper.readTree(response.getBody());
        assertTrue(body.get("created").asBoolean());
        String newId = body.get("sessionId").asText();
        assertTrue(newId.matches("[A-Z0-9]{6}"));
        ArgumentCaptor<GameSessionEntity> saved = ArgumentCaptor.forClass(GameSessionEntity.class);
        verify(sessionRepo).save(saved.capture());
        GameSessionEntity lobby = saved.getValue();
        assertEquals(newId, lobby.getSessionId());
        assertEquals(OLD_ID, lobby.getRematchOf());
        assertEquals(OWNER, lobby.getOwnerId());
        assertFalse(lobby.isStarted());
        assertFalse(lobby.isFinished());
        assertNull(lobby.getFinishedAt());
        assertEquals(2, lobby.getConfig().getDecksCount());
        assertTrue(lobby.getConfig().isVoiceEnabled());
        assertNotNull(lobby.getTtl());
        assertNotNull(lobby.getCreatedAt());
        assertEquals(2, lobby.getPlayers().size());
        assertEquals(OWNER, lobby.getPlayers().get(0).getPlayerId());
        PlayerEntity bot = lobby.getPlayers().get(1);
        assertEquals("BEGINNER", bot.getBotType());
        assertEquals("Beginner Bot 1", bot.getUsername());
        assertTrue(bot.getPlayerId().startsWith(GameSession.BOT_ID_PREFIX));
        verify(sessionRepo).claimRematch(finished, null, newId);
    }

    @Test
    void rematch_lostClaim_deletesOwnLobbyAndJoinsTheWinner() throws JsonProcessingException {
        // Given: the pointer is set by someone else between the read and the conditional write
        GameSessionEntity unclaimed = finishedGame(null);
        GameSessionEntity claimed = finishedGame(LOBBY_ID);
        when(sessionRepo.get(OLD_ID)).thenReturn(unclaimed, claimed);
        when(sessionRepo.get(LOBBY_ID)).thenReturn(lobby(false));
        when(sessionRepo.claimRematch(any(), any(), any())).thenReturn(false);

        // When
        APIGatewayProxyResponseEvent response = rematch.apply(request(HUMAN));

        // Then
        JsonNode body = mapper.readTree(response.getBody());
        assertEquals(LOBBY_ID, body.get("sessionId").asText());
        assertFalse(body.get("created").asBoolean());
        verify(sessionRepo).delete(any());
    }

    @Test
    void rematch_secondCaller_joinsExistingLobby() throws JsonProcessingException {
        // Given: the first caller already created the lobby
        when(sessionRepo.get(OLD_ID)).thenReturn(finishedGame(LOBBY_ID));
        when(sessionRepo.get(LOBBY_ID)).thenReturn(lobby(false));
        when(userRepo.get(HUMAN)).thenReturn(UserProfile.builder().userId(HUMAN).username("Human").build());

        // When
        APIGatewayProxyResponseEvent response = rematch.apply(request(HUMAN));

        // Then
        assertEquals(200, response.getStatusCode());
        JsonNode body = mapper.readTree(response.getBody());
        assertEquals(LOBBY_ID, body.get("sessionId").asText());
        assertFalse(body.get("created").asBoolean());
        ArgumentCaptor<GameSessionEntity> saved = ArgumentCaptor.forClass(GameSessionEntity.class);
        verify(sessionRepo).save(saved.capture());
        assertTrue(saved.getValue().getPlayers().stream().anyMatch(player -> HUMAN.equals(player.getPlayerId())));
        assertEquals(OLD_ID, saved.getValue().getRematchOf());
        verify(sessionRepo, never()).claimRematch(any(), any(), any());
    }

    @Test
    void rematch_callerAlreadyInLobby_isIdempotent() throws JsonProcessingException {
        // Given
        when(sessionRepo.get(OLD_ID)).thenReturn(finishedGame(LOBBY_ID));
        when(sessionRepo.get(LOBBY_ID)).thenReturn(lobby(false));

        // When
        APIGatewayProxyResponseEvent response = rematch.apply(request(OWNER));

        // Then
        assertEquals(200, response.getStatusCode());
        assertFalse(mapper.readTree(response.getBody()).get("created").asBoolean());
        verify(sessionRepo, never()).save(any());
    }

    @Test
    void rematch_gameNotFinished_returns409() {
        // Given
        GameSessionEntity running = finishedGame(null);
        running.setFinished(false);
        when(sessionRepo.get(OLD_ID)).thenReturn(running);

        // When
        APIGatewayProxyResponseEvent response = rematch.apply(request(OWNER));

        // Then
        assertEquals(409, response.getStatusCode());
        assertEquals("{\"error\":\"Game has not finished\"}", response.getBody());
        verify(sessionRepo, never()).save(any());
    }

    @Test
    void rematch_nonPlayer_returns403AndUnknownGame404() {
        // Given
        when(sessionRepo.get(OLD_ID)).thenReturn(finishedGame(null));

        // When / Then
        assertEquals(403, rematch.apply(request(STRANGER)).getStatusCode());
        assertEquals(404, rematch.apply(request(OWNER, "NOPE99")).getStatusCode());
        verify(sessionRepo, never()).save(any());
    }

    @Test
    void rematch_noSub_returns401() {
        APIGatewayProxyRequestEvent noAuth = new APIGatewayProxyRequestEvent()
                .withPathParameters(Map.of("sessionId", OLD_ID));

        assertEquals(401, rematch.apply(noAuth).getStatusCode());
    }

    @Test
    void rematch_lobbyAlreadyStarted_returns409() {
        // Given: the rematch lobby was dealt
        when(sessionRepo.get(OLD_ID)).thenReturn(finishedGame(LOBBY_ID));
        GameSessionEntity started = lobby(false);
        started.setStarted(true);
        when(sessionRepo.get(LOBBY_ID)).thenReturn(started);

        // When
        APIGatewayProxyResponseEvent response = rematch.apply(request(HUMAN));

        // Then
        assertEquals(409, response.getStatusCode());
        assertEquals("{\"error\":\"Rematch already started\"}", response.getBody());
    }

    @Test
    void rematch_flagStartedOnFinishedGame_returns409() {
        // Given
        GameSessionEntity finished = finishedGame(LOBBY_ID);
        finished.setRematchStarted(true);
        when(sessionRepo.get(OLD_ID)).thenReturn(finished);

        // When / Then
        assertEquals("{\"error\":\"Rematch already started\"}", rematch.apply(request(HUMAN)).getBody());
    }

    @Test
    void finish_setsFinishedAtOnTheEntity() {
        // Given: a bots-only game that cannot progress
        GameSession session = GameSession.builder().sessionId(OLD_ID).config(GameConfig.defaultGameConfig()).build();
        session.addBot(BotType.BEGINNER);
        session.addBot(BotType.BEGINNER);
        long before = System.currentTimeMillis();

        // When
        session.finishStalledBotGame();

        // Then
        GameSessionEntity entity = session.toEntity();
        assertTrue(entity.isFinished());
        assertNotNull(entity.getFinishedAt());
        assertTrue(entity.getFinishedAt() >= before);
        assertEquals(entity.getFinishedAt(), SessionMapper.fromEntity(entity).getFinishedAt());
    }

    @Test
    void startGame_ofRematchLobby_setsRematchStartedOnFinishedGame() {
        // Given
        GameSessionEntity lobby = lobby(true);
        GameSessionEntity previous = finishedGame(LOBBY_ID);
        when(sessionRepo.get(LOBBY_ID)).thenReturn(lobby);
        when(sessionRepo.get(OLD_ID)).thenReturn(previous);

        // When
        APIGatewayProxyResponseEvent response = startGame.apply(startRequest(OWNER));

        // Then
        assertEquals(200, response.getStatusCode());
        ArgumentCaptor<GameSessionEntity> saved = ArgumentCaptor.forClass(GameSessionEntity.class);
        verify(sessionRepo, org.mockito.Mockito.times(2)).save(saved.capture());
        assertTrue(saved.getAllValues().get(0).isStarted());
        assertEquals(OLD_ID, saved.getAllValues().get(0).getRematchOf());
        assertEquals(OLD_ID, saved.getAllValues().get(1).getSessionId());
        assertTrue(saved.getAllValues().get(1).isRematchStarted());
    }

    @Test
    void startGame_ofRematchLobby_succeedsWhenFinishedGameIsGone() {
        // Given
        when(sessionRepo.get(LOBBY_ID)).thenReturn(lobby(true));
        when(sessionRepo.get(OLD_ID)).thenReturn(null);

        // When / Then
        assertEquals(200, startGame.apply(startRequest(OWNER)).getStatusCode());
    }

    private static GameSessionEntity finishedGame(String rematchSessionId) {
        GameSession session = GameSession.builder()
                .sessionId(OLD_ID)
                .ownerId(OWNER)
                .config(GameConfig.builder().decksCount(2).faceDownCount(3).faceUpCount(3).handCount(3)
                        .burnCount(6).voiceEnabled(true).build())
                .build();
        session.addPlayer(OWNER, "Owner");
        session.addPlayer(HUMAN, "Human");
        session.addBot(BotType.BEGINNER);
        session.setStarted(true);
        session.setFinished(true);
        session.setFinishedAt(1_700_000_000_000L);
        session.setRematchSessionId(rematchSessionId);
        return session.toEntity();
    }

    /** The rematch lobby: owner and one bot, or owner, human and bot when {@code full}. */
    private static GameSessionEntity lobby(boolean full) {
        GameSession session = GameSession.builder()
                .sessionId(LOBBY_ID)
                .ownerId(OWNER)
                .config(GameConfig.builder().decksCount(2).faceDownCount(3).faceUpCount(3).handCount(3)
                        .burnCount(6).build())
                .rematchOf(OLD_ID)
                .build();
        session.addPlayer(OWNER, "Owner");
        if (full) {
            session.addPlayer(HUMAN, "Human");
        }
        session.addBot(BotType.BEGINNER);
        return session.toEntity();
    }

    private static APIGatewayProxyRequestEvent request(String sub) {
        return request(sub, OLD_ID);
    }

    private static APIGatewayProxyRequestEvent request(String sub, String sessionId) {
        return new APIGatewayProxyRequestEvent()
                .withHttpMethod("POST")
                .withPathParameters(Map.of("sessionId", sessionId))
                .withRequestContext(context(sub));
    }

    private static APIGatewayProxyRequestEvent startRequest(String sub) {
        return new APIGatewayProxyRequestEvent()
                .withHttpMethod("POST")
                .withBody("{\"sessionId\":\"" + LOBBY_ID + "\"}")
                .withRequestContext(context(sub));
    }

    private static APIGatewayProxyRequestEvent.ProxyRequestContext context(String sub) {
        APIGatewayProxyRequestEvent.ProxyRequestContext context = new APIGatewayProxyRequestEvent.ProxyRequestContext();
        context.setAuthorizer(Map.of("claims", Map.of("sub", sub)));
        return context;
    }
}
