package com.tamaspinter.backend.service;

import com.tamaspinter.backend.entity.GameConfigEntity;
import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.entity.PlayerEntity;
import com.tamaspinter.backend.repository.GameSessionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GameBrowseServiceTest {

    private final GameSessionRepository sessionRepo = mock(GameSessionRepository.class);
    private final GameBrowseService service = new GameBrowseService(sessionRepo);

    @Test
    void listOpenGames_skipsFinishedGames_andSortsNewestFirst() {
        // Given
        GameSessionEntity older = game("g-old", "2026-10-01T10:00:00Z", false, false, 1);
        GameSessionEntity finished = game("g-done", "2026-10-09T10:00:00Z", false, true, 2);
        GameSessionEntity newest = game("g-new", "2026-10-08T10:00:00Z", false, false, 2);
        when(sessionRepo.findAll()).thenReturn(List.of(older, finished, newest));

        // When
        List<GameBrowseService.OpenGameView> games = service.listOpenGames();

        // Then
        assertEquals(List.of("g-new", "g-old"), games.stream().map(GameBrowseService.OpenGameView::sessionId).toList());
    }

    @Test
    void listOpenGames_reportsStatusOwnerAndPlayerCount() {
        // Given: a started game owned by "p1", and a waiting game whose owner has left
        GameSessionEntity started = game("g-live", "2026-10-08T10:00:00Z", true, false, 3);
        started.setOwnerId("p1");
        GameSessionEntity waiting = game("g-wait", "2026-10-07T10:00:00Z", false, false, 2);
        waiting.setOwnerId("gone");
        when(sessionRepo.findAll()).thenReturn(List.of(started, waiting));

        // When
        List<GameBrowseService.OpenGameView> games = service.listOpenGames();

        // Then
        GameBrowseService.OpenGameView live = games.get(0);
        assertEquals("in_progress", live.status());
        assertEquals("name-p1", live.ownerName());
        assertEquals(3, live.playerCount());
        GameBrowseService.OpenGameView wait = games.get(1);
        assertEquals("waiting", wait.status());
        assertEquals("Unknown", wait.ownerName());
    }

    @Test
    void listOpenGames_capsResultsAtFifty() {
        // Given
        List<GameSessionEntity> many = new ArrayList<>();
        IntStream.range(0, 60).forEach(i -> many.add(game("g" + i, String.format("2026-10-%02dT00:00:00Z", (i % 28) + 1),
                false, false, 1)));
        when(sessionRepo.findAll()).thenReturn(many);

        // When / Then
        assertEquals(50, service.listOpenGames().size());
    }

    @Test
    void listOpenGames_skipsGamesWhoseTtlHasPassed() {
        // Given: an expired game (DynamoDB TTL deletion lags), a live one, and one without a ttl
        long now = Instant.now().getEpochSecond();
        GameSessionEntity expired = game("g-expired", "2026-10-08T10:00:00Z", true, false, 2);
        expired.setTtl(now - 60);
        GameSessionEntity live = game("g-live", "2026-10-07T10:00:00Z", false, false, 1);
        live.setTtl(now + 3600);
        GameSessionEntity noTtl = game("g-no-ttl", "2026-10-06T10:00:00Z", false, false, 1);
        when(sessionRepo.findAll()).thenReturn(List.of(expired, live, noTtl));

        // When
        List<String> ids = service.listOpenGames().stream().map(GameBrowseService.OpenGameView::sessionId).toList();

        // Then
        assertEquals(List.of("g-live", "g-no-ttl"), ids);
    }

    @Test
    void maxPlayers_isDerivedFromDeckSizeAndCardsPerPlayer() {
        // Given: one deck, 3 + 3 + 3 cards per player -> 52 / 9 = 5
        GameConfigEntity oneDeck = GameConfigEntity.builder().decksCount(1).build();
        // Given: two decks, 9 cards per player -> 104 / 9 = 11, capped at 6
        GameConfigEntity twoDecks = GameConfigEntity.builder().decksCount(2).build();
        // Given: a layout that uses 18 cards per player -> 52 / 18 = 2
        GameConfigEntity bigLayout = GameConfigEntity.builder().decksCount(1).faceDownCount(6).faceUpCount(6).handCount(6).build();

        // When / Then
        assertEquals(5, GameBrowseService.maxPlayers(oneDeck));
        assertEquals(6, GameBrowseService.maxPlayers(twoDecks));
        assertEquals(2, GameBrowseService.maxPlayers(bigLayout));
    }

    @Test
    void listOpenGames_usesDefaultConfigWhenMissing() {
        // Given
        GameSessionEntity bare = game("g-bare", "2026-10-08T10:00:00Z", false, false, 1);
        bare.setConfig(null);
        when(sessionRepo.findAll()).thenReturn(List.of(bare));

        // When
        GameBrowseService.OpenGameView view = service.listOpenGames().get(0);

        // Then
        assertEquals(5, view.maxPlayers());
        assertEquals(1, view.decksCount());
    }

    private static GameSessionEntity game(String id, String createdAt, boolean started, boolean finished, int players) {
        List<PlayerEntity> seats = new ArrayList<>();
        for (int i = 0; i < players; i++) {
            seats.add(PlayerEntity.builder().playerId("p" + (i + 1)).username("name-p" + (i + 1)).build());
        }
        return GameSessionEntity.builder()
                .sessionId(id)
                .createdAt(createdAt)
                .started(started)
                .finished(finished)
                .players(seats)
                .config(GameConfigEntity.builder().decksCount(1).build())
                .build();
    }
}
