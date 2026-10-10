package com.tamaspinter.backend.config;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.mapper.SessionMapper;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameFunctionConfigTest {

    private static final String SESSION_ID = "ABC123";
    private static final String OWNER = "owner-1";
    private static final String OTHER = "player-2";
    private static final String NEWCOMER = "player-6";
    private static final String TWO_DECKS_BODY = "{\"decksCount\":2}";
    private static final String FULL_ONE_DECK_BODY =
            "{\"message\":\"Game is full: one deck seats 5 players. The owner can add a second deck.\"}";

    private final ObjectMapper mapper = new ObjectMapper();
    private GameSessionRepository sessionRepo;
    private BlockedUserGuard blockedUserGuard;
    private Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> raiseDecks;
    private Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> joinGame;
    private Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> getState;

    @BeforeAll
    static void configureRegion() {
        // The config builds a DynamoDB client eagerly; the SDK only needs a region to construct it.
        System.setProperty("aws.region", "eu-central-1");
    }

    @BeforeEach
    void setUp() {
        sessionRepo = mock(GameSessionRepository.class);
        blockedUserGuard = mock(BlockedUserGuard.class);
        GameFunctionConfig config = new GameFunctionConfig(sessionRepo, mock(UserProfileRepository.class),
                blockedUserGuard, mapper);
        raiseDecks = config.raiseDecks();
        joinGame = config.joinGame();
        getState = config.getState();
    }

    @Test
    void getState_carriesDecksCountOneThenTwo() throws JsonProcessingException {
        // Given: an unstarted one-deck lobby
        when(sessionRepo.get(SESSION_ID)).thenReturn(lobby(1, 2));

        // When: the state is read before and after the owner raises the deck
        JsonNode oneDeck = mapper.readTree(getState.apply(stateRequest(OWNER)).getBody());
        raiseDecks.apply(request(OWNER, TWO_DECKS_BODY));
        JsonNode twoDecks = mapper.readTree(getState.apply(stateRequest(OWNER)).getBody());

        // Then: the new decksCount field follows the deck count, and the draw-pile deckCount is still there
        assertEquals(1, oneDeck.get("decksCount").asInt());
        assertEquals(2, twoDecks.get("decksCount").asInt());
        assertTrue(twoDecks.has("deckCount"));
    }

    @Test
    void raiseDecks_ownerBeforeStart_setsTwoDecksAndBurnCount() {
        // Given: an unstarted one-deck lobby with two players, owned by OWNER
        GameSessionEntity lobby = lobby(1, 2);
        when(sessionRepo.get(SESSION_ID)).thenReturn(lobby);

        // When
        APIGatewayProxyResponseEvent response = raiseDecks.apply(request(OWNER, TWO_DECKS_BODY));

        // Then
        assertEquals(200, response.getStatusCode());
        assertEquals("{\"decksCount\":2,\"burnCount\":6}", response.getBody());
        ArgumentCaptor<GameSessionEntity> saved = ArgumentCaptor.forClass(GameSessionEntity.class);
        verify(sessionRepo).save(saved.capture());
        assertEquals(2, saved.getValue().getConfig().getDecksCount());
        assertEquals(6, saved.getValue().getConfig().getBurnCount());
        assertEquals(2, saved.getValue().getPlayers().size());
    }

    @Test
    void raiseDecks_nonOwner_isForbiddenAndNothingIsSaved() {
        // Given: an unstarted one-deck lobby owned by someone else
        when(sessionRepo.get(SESSION_ID)).thenReturn(lobby(1, 2));

        // When
        APIGatewayProxyResponseEvent response = raiseDecks.apply(request(OTHER, TWO_DECKS_BODY));

        // Then
        assertEquals(403, response.getStatusCode());
        verify(sessionRepo, never()).save(any(GameSessionEntity.class));
    }

    @Test
    void raiseDecks_blockedOwner_isForbiddenWithBlockedMessage() {
        // Given: the owner of an unstarted lobby has been blocked
        when(sessionRepo.get(SESSION_ID)).thenReturn(lobby(1, 2));
        when(blockedUserGuard.isBlocked(OWNER)).thenReturn(true);

        // When
        APIGatewayProxyResponseEvent response = raiseDecks.apply(request(OWNER, TWO_DECKS_BODY));

        // Then
        assertEquals(403, response.getStatusCode());
        assertEquals(BlockedUserGuard.BLOCKED_BODY, response.getBody());
        verify(sessionRepo, never()).save(any(GameSessionEntity.class));
    }

    @Test
    void raiseDecks_unknownSession_isNotFound() {
        // Given: no game with that id
        when(sessionRepo.get(SESSION_ID)).thenReturn(null);

        // When
        APIGatewayProxyResponseEvent response = raiseDecks.apply(request(OWNER, TWO_DECKS_BODY));

        // Then
        assertEquals(404, response.getStatusCode());
        verify(sessionRepo, never()).save(any(GameSessionEntity.class));
    }

    @Test
    void raiseDecks_afterStart_isConflictWithStartedMessage() {
        // Given: a started one-deck game
        GameSessionEntity started = lobby(1, 2);
        started.setStarted(true);
        when(sessionRepo.get(SESSION_ID)).thenReturn(started);

        // When
        APIGatewayProxyResponseEvent response = raiseDecks.apply(request(OWNER, TWO_DECKS_BODY));

        // Then
        assertEquals(409, response.getStatusCode());
        assertEquals("{\"message\":\"Game already started\"}", response.getBody());
        verify(sessionRepo, never()).save(any(GameSessionEntity.class));
    }

    @Test
    void raiseDecks_valueOtherThanTwo_isBadRequestAndNothingIsSaved() {
        // Given: an unstarted one-deck lobby
        when(sessionRepo.get(SESSION_ID)).thenReturn(lobby(1, 2));

        // When / Then: a deck count of one or three, a string, a missing value and a non-JSON body are refused
        assertEquals(400, raiseDecks.apply(request(OWNER, "{\"decksCount\":1}")).getStatusCode());
        assertEquals(400, raiseDecks.apply(request(OWNER, "{\"decksCount\":3}")).getStatusCode());
        assertEquals(400, raiseDecks.apply(request(OWNER, "{\"decksCount\":\"2\"}")).getStatusCode());
        assertEquals(400, raiseDecks.apply(request(OWNER, "{}")).getStatusCode());
        assertEquals(400, raiseDecks.apply(request(OWNER, "not json")).getStatusCode());
        verify(sessionRepo, never()).save(any(GameSessionEntity.class));
    }

    @Test
    void raiseDecks_alreadyTwoDecks_isConflictAndNothingIsSaved() {
        // Given: an unstarted lobby that already uses two decks
        when(sessionRepo.get(SESSION_ID)).thenReturn(lobby(2, 2));

        // When
        APIGatewayProxyResponseEvent response = raiseDecks.apply(request(OWNER, TWO_DECKS_BODY));

        // Then
        assertEquals(409, response.getStatusCode());
        assertEquals("{\"message\":\"This game already uses two decks\"}", response.getBody());
        verify(sessionRepo, never()).save(any(GameSessionEntity.class));
    }

    @Test
    void joinGame_oneDeckAtFive_isConflictWithDeckMessageAndNothingIsSaved() {
        // Given: a one-deck lobby with five players, its capacity
        when(sessionRepo.get(SESSION_ID)).thenReturn(lobby(1, 5));

        // When
        APIGatewayProxyResponseEvent response = joinGame.apply(join(NEWCOMER));

        // Then
        assertEquals(409, response.getStatusCode());
        assertEquals(FULL_ONE_DECK_BODY, response.getBody());
        verify(sessionRepo, never()).save(any(GameSessionEntity.class));
    }

    @Test
    void raiseDecks_thenJoin_seatsSixthPlayer() {
        // Given: a one-deck lobby with five players, owned by OWNER
        when(sessionRepo.get(SESSION_ID)).thenReturn(lobby(1, 5));

        // When: the owner raises the deck, then a sixth player joins
        APIGatewayProxyResponseEvent raised = raiseDecks.apply(request(OWNER, TWO_DECKS_BODY));
        APIGatewayProxyResponseEvent joined = joinGame.apply(join(NEWCOMER));

        // Then
        assertEquals(200, raised.getStatusCode());
        assertEquals(200, joined.getStatusCode());
        ArgumentCaptor<GameSessionEntity> saved = ArgumentCaptor.forClass(GameSessionEntity.class);
        verify(sessionRepo, times(2)).save(saved.capture());
        assertEquals(6, saved.getValue().getPlayers().size());
        assertEquals(2, saved.getValue().getConfig().getDecksCount());
    }

    /** A stored lobby with the given deck count and seated players; the first seat belongs to OWNER. */
    private static GameSessionEntity lobby(int decksCount, int players) {
        GameSession session = GameSession.builder()
                .sessionId(SESSION_ID)
                .ownerId(OWNER)
                .config(GameConfig.builder()
                        .decksCount(decksCount)
                        .faceDownCount(3)
                        .faceUpCount(3)
                        .handCount(3)
                        .burnCount(decksCount == 2 ? 6 : 4)
                        .build())
                .build();
        for (int i = 0; i < players; i++) {
            session.addPlayer(i == 0 ? OWNER : "p" + i, "name" + i);
        }
        return SessionMapper.toEntity(session);
    }

    private static APIGatewayProxyRequestEvent request(String sub, String body) {
        return new APIGatewayProxyRequestEvent()
                .withHttpMethod("POST")
                .withPathParameters(Map.of("sessionId", SESSION_ID))
                .withBody(body)
                .withRequestContext(context(sub));
    }

    private static APIGatewayProxyRequestEvent stateRequest(String sub) {
        return new APIGatewayProxyRequestEvent()
                .withHttpMethod("GET")
                .withPathParameters(Map.of("sessionId", SESSION_ID))
                .withRequestContext(context(sub));
    }

    private static APIGatewayProxyRequestEvent join(String sub) {
        return new APIGatewayProxyRequestEvent()
                .withHttpMethod("POST")
                .withBody("{\"sessionId\":\"" + SESSION_ID + "\"}")
                .withRequestContext(context(sub));
    }

    private static APIGatewayProxyRequestEvent.ProxyRequestContext context(String sub) {
        Map<String, Object> claims = Map.of("sub", sub);
        APIGatewayProxyRequestEvent.ProxyRequestContext context = new APIGatewayProxyRequestEvent.ProxyRequestContext();
        context.setAuthorizer(Map.of("claims", claims));
        return context;
    }
}
