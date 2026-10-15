package com.tamaspinter.backend.config;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamaspinter.backend.bot.BotType;
import com.tamaspinter.backend.entity.EloChangeEntity;
import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.entity.PlayerEntity;
import com.tamaspinter.backend.game.CardSelection;
import com.tamaspinter.backend.game.CardSource;
import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.mapper.SessionMapper;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.Deck;
import com.tamaspinter.backend.model.Player;
import com.tamaspinter.backend.model.Suit;
import com.tamaspinter.backend.model.UserProfile;
import com.tamaspinter.backend.repository.GameSessionRepository;
import com.tamaspinter.backend.repository.UserProfileRepository;
import com.tamaspinter.backend.service.BlockedUserGuard;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GameFunctionConfigBotsTest {

    private static final String SESSION_ID = "ABC123";
    private static final String ENDPOINT = "https://api.test/prod";
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
    void updateElo_botIsShithead_humansGainAndBotTypeLoses() {
        // Given: a finished game where the bot lost
        GameSession session = finishedGame(BOT);
        stubRatings(1000, 1000, 1000);

        // When
        Map<String, EloChangeEntity> changes = config.updateElo(session);

        // Then: both humans gain, the bot seat shows its type's loss, and the type row is written once
        assertEquals(3, changes.size());
        assertTrue(changes.get(OWNER).getAfter() > changes.get(OWNER).getBefore());
        assertTrue(changes.get(HUMAN).getAfter() > changes.get(HUMAN).getBefore());
        assertTrue(changes.get(BOT).getAfter() < changes.get(BOT).getBefore());
        verify(userRepo).updateBotTypeRating(
                org.mockito.ArgumentMatchers.eq(BotType.BEGINNER),
                org.mockito.ArgumentMatchers.eq(1000.0),
                org.mockito.ArgumentMatchers.eq(changes.get(BOT).getAfter()));
    }

    @Test
    void updateElo_humanIsShithead_botTypeWinsAndSeatShowsTypeRating() {
        // Given: a finished game where the owner lost, with a bot at the table
        GameSession session = finishedGame(OWNER);
        stubRatings(1000, 1000, 1100);

        // When
        Map<String, EloChangeEntity> changes = config.updateElo(session);

        // Then
        assertEquals(3, changes.size());
        assertTrue(changes.get(OWNER).getAfter() < changes.get(OWNER).getBefore());
        assertTrue(changes.get(HUMAN).getAfter() > changes.get(HUMAN).getBefore());
        assertEquals(1100.0, changes.get(BOT).getBefore(), 0.001);
        assertTrue(changes.get(BOT).getAfter() > 1100.0);
    }

    @Test
    void updateElo_onlyBots_changesNothing() {
        // Given: a game with bots only
        GameSession session = GameSession.builder().sessionId(SESSION_ID).ownerId(OWNER).build();
        session.getPlayers().add(Player.builder().playerId("b1").username("b1").botType(BotType.BEGINNER).build());
        session.getPlayers().add(Player.builder().playerId("b2").username("b2").botType(BotType.INTERMEDIATE).build());
        session.setFinished(true);
        session.setShitheadId("b1");

        // When / Then
        assertTrue(config.updateElo(session).isEmpty());
        verifyNoInteractions(userRepo);
    }

    @Test
    void updateElo_ratingRaceOnTheBotType_recomputesBeforeWritingAnyHuman() {
        // Given: the first conditional write loses a race, the second wins
        GameSession session = finishedGame(OWNER);
        stubRatings(1000, 1000, 1000);
        when(userRepo.updateBotTypeRating(any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble())).thenReturn(false, true);

        // When
        Map<String, EloChangeEntity> changes = config.updateElo(session);

        // Then: humans are saved once, after the successful type write
        assertEquals(3, changes.size());
        verify(userRepo, org.mockito.Mockito.times(2)).getOrCreateBotType(BotType.BEGINNER);
        verify(userRepo, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void updateElo_ratingRaceKeepsLosing_givesUpWithoutTouchingHumans() {
        GameSession session = finishedGame(OWNER);
        stubRatings(1000, 1000, 1000);
        when(userRepo.updateBotTypeRating(any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble())).thenReturn(false);

        assertTrue(config.updateElo(session).isEmpty());
        verify(userRepo, never()).save(any());
    }

    @Test
    void leaderboardSession_botSeat_isShownUnderItsSeatNameWithItsTypeRating() throws JsonProcessingException {
        // Given: the owner and a bot whose type is rated 1234
        when(sessionRepo.get(SESSION_ID)).thenReturn(lobbyWithBot());
        UserProfile typeRow = UserProfile.builder().userId("bot-type-BEGINNER").username("Beginner Bot")
                .eloScore(1234).bot(true).games(7L).build();
        when(userRepo.batchGet(anyList())).thenReturn(List.of(profile(OWNER), typeRow));

        // When
        JsonNode rows = mapper.readTree(config.leaderboardSession().apply(stateRequest(OWNER)).getBody());

        // Then
        assertEquals(2, rows.size());
        assertEquals("Beginner Bot 1", rows.get(1).get("username").asText());
        assertEquals(1234.0, rows.get(1).get("eloScore").asDouble(), 0.001);
        assertTrue(rows.get(1).get("bot").asBoolean());
        assertNull(rows.get(0).get("bot"));
    }

    @Test
    void leaderboardTop_botTypeRow_isFlaggedAsBotWithGames() throws JsonProcessingException {
        when(userRepo.topLeaderboard(20)).thenReturn(List.of(
                UserProfile.builder().userId("bot-type-BEGINNER").username("Beginner Bot").eloScore(1010)
                        .bot(true).games(3L).build(),
                profile(OWNER)));

        JsonNode rows = mapper.readTree(config.leaderboardTop().apply(new APIGatewayProxyRequestEvent()).getBody());

        assertTrue(rows.get(0).get("bot").asBoolean());
        assertEquals(3, rows.get(0).get("games").asInt());
        assertNull(rows.get(1).get("bot"));
        assertNull(rows.get(1).get("games"));
    }

    /** Stubs the profiles of the owner and the human and the BEGINNER type row with the given ratings. */
    private void stubRatings(double owner, double human, double beginnerType) {
        when(userRepo.batchGet(anyList())).thenReturn(List.of(ratedProfile(OWNER, owner), ratedProfile(HUMAN, human)));
        when(userRepo.get(OWNER)).thenReturn(ratedProfile(OWNER, owner));
        when(userRepo.get(HUMAN)).thenReturn(ratedProfile(HUMAN, human));
        when(userRepo.getOrCreateBotType(BotType.BEGINNER)).thenReturn(UserProfile.builder()
                .userId("bot-type-BEGINNER").username("Beginner Bot").eloScore(beginnerType).bot(true).build());
        when(userRepo.updateBotTypeRating(any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble())).thenReturn(true);
    }

    private static UserProfile ratedProfile(String userId, double rating) {
        return UserProfile.builder().userId(userId).username(userId).eloScore(rating).build();
    }

    @Test
    void saveAfterMove_humanStillInPlay_savesOnceAndLeavesTheBotForTheClient() {
        // Given: the owner is to move, and the bot is next. The stored entity has the starting flag set.
        GameSession session = ownerThenBot();
        GameSessionEntity stored = SessionMapper.toEntity(session);
        stored.setStarting(true);
        session.playSelections(List.of(new CardSelection(CardSource.HAND, 0)));
        List<String> log = new ArrayList<>();
        GameFunctionConfig recording = recordingConfig(log, OWNER);
        recordSaves(log);

        // When
        GameSessionEntity updated = recording.saveAfterMove(stored, session, SESSION_ID, ENDPOINT, null);

        // Then: one save, no bot move; the bot waits for the client's bot-turn request
        assertEquals(List.of("save current=Beginner Bot 1 pile=1"), log);
        assertTrue(updated.isStarting());
        assertEquals(BOT, updated.getCurrentPlayerId());
    }

    @Test
    void saveAfterMove_noHumanLeftInPlay_playsTheBotTurnsInThisInvocation() {
        // Given: the owner has just played their last card and is out; only the bot is left in play
        GameSession session = ownerThenBot();
        GameSessionEntity stored = SessionMapper.toEntity(session);
        session.playSelections(List.of(new CardSelection(CardSource.HAND, 0)));
        Player owner = session.getPlayers().get(0);
        owner.getFaceUp().clear();
        owner.setOut(true);
        List<String> log = new ArrayList<>();
        GameFunctionConfig recording = recordingConfig(log, OWNER);
        recordSaves(log);
        stubRatings(1000, 1000, 1000);

        // When
        GameSessionEntity updated = recording.saveAfterMove(stored, session, SESSION_ID, ENDPOINT, null);

        // Then: the bot's move is saved and broadcast here (save, broadcast, final save, then the Elo save: games a bot loses are rated now); the game then ends
        // with the bot as shithead
        assertEquals(4, log.size());
        assertTrue(log.get(1).startsWith("broadcast"));
        assertEquals(2, updated.getDiscardPile().size());
        assertTrue(updated.isFinished());
        assertEquals(BOT, updated.getShitheadId());
    }

    @Test
    void playCardWS_humanPlaysWithBotNext_broadcastsOnceAndDoesNotMoveTheBot() {
        // Given: the owner is to move, the bot is next, and the human after the owner is still in play
        List<String> log = new ArrayList<>();
        GameFunctionConfig recording = recordingConfig(log, OWNER);
        recordSaves(log);
        when(sessionRepo.get(SESSION_ID)).thenReturn(SessionMapper.toEntity(ownerThenBot()));

        // When: the owner plays their 5 from the hand
        APIGatewayProxyResponseEvent response = recording.playCardWS().apply(webSocketEvent(
                "{\"action\":\"play\",\"sessionId\":\"" + SESSION_ID
                        + "\",\"selections\":[{\"source\":\"HAND\",\"index\":0}]}"));

        // Then: one save and one broadcast of the owner's move; the bot has not played
        assertEquals(200, response.getStatusCode());
        assertEquals(List.of(
                "save current=Beginner Bot 1 pile=1",
                "broadcast current=Beginner Bot 1 pile=1"), log);
    }

    @Test
    void botTurn_route_isRegistered() {
        // Given
        ApiRoutes routes = new ApiRoutes(config, mock(AccountManagementFunctionConfig.class),
                mock(VoiceFunctionConfig.class));

        // When / Then
        assertTrue(routes.findRest("POST", "/prod/games/ABC/bot-turn").isPresent());
        assertFalse(routes.findRest("GET", "/prod/games/ABC/bot-turn").isPresent());
    }

    @Test
    void botTurn_humanInPlayAndBotNext_playsExactlyOneBotMoveAndSavesOnce() {
        // Given: the bot is to move with a human still in play, and the request carries the expected token
        List<String> log = new ArrayList<>();
        GameFunctionConfig recording = recordingConfig(log, OWNER);
        recordSaves(log);
        when(sessionRepo.get(SESSION_ID)).thenReturn(SessionMapper.toEntity(botThenOwnerThenHuman()));

        // When
        APIGatewayProxyResponseEvent response = recording.botTurn().apply(
                request(OWNER, "{\"expectedPlayerId\":\"" + BOT + "\",\"expectedMoves\":0}"));

        // Then: the bot's 5 went out, once, and the owner is next; the state is returned
        assertEquals(200, response.getStatusCode());
        assertEquals(List.of("save current=owner pile=1", "broadcast current=owner pile=1"), log);
        assertEquals(OWNER, stateOf(response).get("currentPlayerId").asText());
    }

    @Test
    void botTurn_repeatedRequestAfterTheMove_changesNothing() {
        // Given: the same request is sent twice (two clients, or a retry); the repository holds the latest save
        List<String> log = new ArrayList<>();
        GameFunctionConfig recording = recordingConfig(log, OWNER);
        GameSessionEntity[] stored = {SessionMapper.toEntity(botThenOwnerThenHuman())};
        when(sessionRepo.get(SESSION_ID)).thenAnswer(invocation -> stored[0]);
        doAnswer(invocation -> {
            stored[0] = invocation.getArgument(0);
            log.add("save " + describe(stored[0]));
            return null;
        }).when(sessionRepo).save(any());
        String body = "{\"expectedPlayerId\":\"" + BOT + "\",\"expectedMoves\":0}";
        recording.botTurn().apply(request(OWNER, body));
        int savesAfterFirst = log.size();

        // When: the second request arrives with the old token
        APIGatewayProxyResponseEvent second = recording.botTurn().apply(request(OWNER, body));

        // Then: nothing is saved or broadcast again, and the state shows the owner to move
        assertEquals(200, second.getStatusCode());
        assertEquals(savesAfterFirst, log.size());
        assertEquals(OWNER, stateOf(second).get("currentPlayerId").asText());
    }

    @Test
    void botTurn_staleEventToken_changesNothing() {
        // Given: the bot is to move, but the token names an older point of the game
        List<String> log = new ArrayList<>();
        GameFunctionConfig recording = recordingConfig(log, OWNER);
        recordSaves(log);
        when(sessionRepo.get(SESSION_ID)).thenReturn(SessionMapper.toEntity(botThenOwnerThenHuman()));

        // When
        APIGatewayProxyResponseEvent response = recording.botTurn().apply(
                request(OWNER, "{\"expectedPlayerId\":\"" + BOT + "\",\"expectedMoves\":7}"));

        // Then
        assertEquals(200, response.getStatusCode());
        assertTrue(log.isEmpty());
        assertEquals(BOT, stateOf(response).get("currentPlayerId").asText());
    }

    @Test
    void botTurn_callerNotSeatedAsHuman_isForbiddenAndNothingIsSaved() {
        // Given: a stranger, and a bot's own id as the caller
        when(sessionRepo.get(SESSION_ID)).thenReturn(SessionMapper.toEntity(botThenOwnerThenHuman()));
        String body = "{\"expectedPlayerId\":\"" + BOT + "\",\"expectedMoves\":0}";

        // When / Then
        assertEquals(403, config.botTurn().apply(request("stranger", body)).getStatusCode());
        assertEquals(403, config.botTurn().apply(request(BOT, body)).getStatusCode());
        verify(sessionRepo, never()).save(any());
    }

    @Test
    void botTurn_noHumanLeftInPlay_playsNothing() {
        // Given: the owner is out, and a bot is to move
        GameSession session = botThenOwnerThenHuman();
        session.getPlayers().get(1).setOut(true);
        session.getPlayers().get(2).setOut(true);
        when(sessionRepo.get(SESSION_ID)).thenReturn(SessionMapper.toEntity(session));

        // When
        APIGatewayProxyResponseEvent response = config.botTurn().apply(
                request(OWNER, "{\"expectedPlayerId\":\"" + BOT + "\",\"expectedMoves\":0}"));

        // Then: the endpoint plays nothing
        assertEquals(200, response.getStatusCode());
        verify(sessionRepo, never()).save(any());
    }

    @Test
    void botTurn_badBody_isBadRequest() {
        // Given
        when(sessionRepo.get(SESSION_ID)).thenReturn(SessionMapper.toEntity(botThenOwnerThenHuman()));

        // When / Then
        assertEquals(400, config.botTurn().apply(request(OWNER, "not json")).getStatusCode());
    }

    private JsonNode stateOf(APIGatewayProxyResponseEvent response) {
        try {
            return mapper.readTree(response.getBody());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A config whose WebSocket broadcasts are recorded in {@code log} instead of posted. */
    private GameFunctionConfig recordingConfig(List<String> log, String webSocketUserId) {
        return new GameFunctionConfig(sessionRepo, userRepo, blockedUserGuard, mapper) {
            @Override
            String websocketUserId(APIGatewayV2WebSocketEvent event) {
                return webSocketUserId;
            }

            @Override
            void broadcastState(String gameSessionId, GameSessionEntity state, String endpoint, Card revealedCard) {
                log.add("broadcast " + describe(state));
            }
        };
    }

    /** Records each session save in {@code log}, in the order the saves happen. */
    private void recordSaves(List<String> log) {
        doAnswer(invocation -> {
            log.add("save " + describe(invocation.getArgument(0)));
            return null;
        }).when(sessionRepo).save(any());
    }

    /** The table as the players can tell it apart: whose turn it is and how many cards lie on the pile. */
    private static String describe(GameSessionEntity state) {
        String current = state.getPlayers().stream()
                .filter(player -> player.getPlayerId().equals(state.getCurrentPlayerId()))
                .map(PlayerEntity::getUsername)
                .findFirst()
                .orElse("none");
        return "current=" + current + " pile=" + state.getDiscardPile().size();
    }

    private static APIGatewayV2WebSocketEvent webSocketEvent(String body) {
        APIGatewayV2WebSocketEvent.RequestContext context = new APIGatewayV2WebSocketEvent.RequestContext();
        context.setConnectionId("conn-1");
        context.setDomainName("api.test");
        context.setStage("prod");
        APIGatewayV2WebSocketEvent event = new APIGatewayV2WebSocketEvent();
        event.setBody(body);
        event.setRequestContext(context);
        return event;
    }

    /** A started table in which the owner is to move: the owner holds a 5 and a face-up 14, the bot a 14 and a face-up 14. */
    private static GameSession ownerThenBot() {
        return table(seat(OWNER, "owner", null, 5, 14), seat(BOT, "Beginner Bot 1", BotType.BEGINNER, 14, 14));
    }

    /**
     * A started table in which the bot is to move: it holds a 5 and a face-up 14. The owner is next with a 14 and a
     * face-up 14, and a third player holds a 3 and a face-up 3.
     */
    private static GameSession botThenOwnerThenHuman() {
        return table(seat(BOT, "Beginner Bot 1", BotType.BEGINNER, 5, 14), seat(OWNER, "owner", null, 14, 14),
                seat(HUMAN, "human", null, 3, 3));
    }

    /** A started game with an empty draw pile and the given seats in turn order, the first seat to move. */
    private static GameSession table(Player... seats) {
        GameSession session = GameSession.builder().sessionId(SESSION_ID).ownerId(OWNER).config(oneDeckConfig()).build();
        session.getPlayers().addAll(List.of(seats));
        session.setStarted(true);
        session.setSetupComplete(true);
        session.setDeck(new Deck(List.of()));
        return session;
    }

    /** A seat with one card to play and one face-up card, so that no seat goes out on its first play. */
    private static Player seat(String playerId, String username, BotType botType, int handValue, int faceUpValue) {
        GameConfig gameConfig = oneDeckConfig();
        Player player = Player.builder().playerId(playerId).username(username).botType(botType).build();
        player.getHand().add(card(gameConfig, handValue));
        player.getFaceUp().add(card(gameConfig, faceUpValue));
        return player;
    }

    private static Card card(GameConfig gameConfig, int value) {
        return Card.builder().suit(Suit.HEARTS).value(value).rule(gameConfig.getCardRule(value)).build();
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
