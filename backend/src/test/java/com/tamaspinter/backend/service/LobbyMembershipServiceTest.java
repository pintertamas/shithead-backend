package com.tamaspinter.backend.service;

import com.tamaspinter.backend.entity.GameConfigEntity;
import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.entity.PlayerEntity;
import com.tamaspinter.backend.repository.GameSessionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LobbyMembershipServiceTest {

    private static final String USER = "player-7";

    @Test
    void removeFromUnstartedLobbies_savesLobbyWithRemainingPlayers_andSkipsStartedOnes() {
        // Given: an unstarted lobby with another player, and a started game with the user
        GameSessionEntity lobby = game("lobby", false, USER, "p2");
        GameSessionEntity started = game("live", true, USER, "p2");
        GameSessionRepository repo = mock(GameSessionRepository.class);
        when(repo.findAll()).thenReturn(List.of(lobby, started));
        LobbyMembershipService service = new LobbyMembershipService(repo);

        // When
        int removed = service.removeFromUnstartedLobbies(USER);

        // Then
        assertEquals(1, removed);
        ArgumentCaptor<GameSessionEntity> saved = ArgumentCaptor.forClass(GameSessionEntity.class);
        verify(repo, times(1)).save(saved.capture());
        assertEquals(List.of("p2"), saved.getValue().getPlayers().stream().map(PlayerEntity::getPlayerId).toList());
        verify(repo, never()).delete("live");
    }

    @Test
    void removeFromUnstartedLobbies_deletesLobbyThatBecomesEmpty() {
        // Given: the user is the only player in the lobby
        GameSessionEntity lobby = game("solo", false, USER);
        GameSessionRepository repo = mock(GameSessionRepository.class);
        when(repo.findAll()).thenReturn(List.of(lobby));
        LobbyMembershipService service = new LobbyMembershipService(repo);

        // When
        int removed = service.removeFromUnstartedLobbies(USER);

        // Then
        assertEquals(1, removed);
        verify(repo).delete("solo");
        verify(repo, never()).save(any(GameSessionEntity.class));
    }

    private static GameSessionEntity game(String id, boolean started, String... playerIds) {
        List<PlayerEntity> players = new ArrayList<>();
        for (String playerId : playerIds) {
            players.add(PlayerEntity.builder()
                    .playerId(playerId)
                    .username(playerId)
                    .hand(List.of())
                    .faceUp(List.of())
                    .faceDown(List.of())
                    .build());
        }
        return GameSessionEntity.builder()
                .sessionId(id)
                .started(started)
                .players(players)
                .discardPile(List.of())
                .config(GameConfigEntity.builder().build())
                .build();
    }
}
