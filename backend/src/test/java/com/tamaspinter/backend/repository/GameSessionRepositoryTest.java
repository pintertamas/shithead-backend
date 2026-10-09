package com.tamaspinter.backend.repository;

import com.tamaspinter.backend.entity.GameSessionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GameSessionRepositoryTest {

    private DynamoDbTable<GameSessionEntity> table;
    private GameSessionRepository repository;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        DynamoDbEnhancedClient enhanced = mock(DynamoDbEnhancedClient.class);
        table = mock(DynamoDbTable.class);
        doReturn(table).when(enhanced).table(anyString(), any(TableSchema.class));
        repository = new GameSessionRepository(enhanced, "games");
    }

    @Test
    void save_stampsUpdatedAtWithCurrentEpochSeconds() {
        // Given
        GameSessionEntity game = GameSessionEntity.builder().sessionId("g1").build();
        long before = Instant.now().getEpochSecond();

        // When
        repository.save(game);

        // Then
        ArgumentCaptor<GameSessionEntity> saved = ArgumentCaptor.forClass(GameSessionEntity.class);
        verify(table).putItem(saved.capture());
        long after = Instant.now().getEpochSecond();
        assertTrue(saved.getValue().getUpdatedAt() >= before && saved.getValue().getUpdatedAt() <= after);
    }
}
