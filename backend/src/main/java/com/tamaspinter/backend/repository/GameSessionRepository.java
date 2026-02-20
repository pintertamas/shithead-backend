package com.tamaspinter.backend.repository;

import com.tamaspinter.backend.entity.GameSessionEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Repository
public class GameSessionRepository {
    private final DynamoDbTable<GameSessionEntity> table;

    public GameSessionRepository(
            DynamoDbEnhancedClient enhancedClient,
            @Value("${dynamodb.sessions.table}") String tableName) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(GameSessionEntity.class));
    }

    public void save(GameSessionEntity session) {
        table.putItem(session);
    }

    public GameSessionEntity get(String sessionId) {
        return table.getItem(r -> r.key(k -> k.partitionValue(sessionId)));
    }
}
