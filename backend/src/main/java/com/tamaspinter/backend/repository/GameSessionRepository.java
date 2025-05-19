package com.tamaspinter.backend.repository;

import com.tamaspinter.backend.entity.GameSessionEntity;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

public class GameSessionRepository {
    private final DynamoDbTable<GameSessionEntity> table =
            DynamoDbClientProvider.client()
                    .table("GameSessions", TableSchema.fromBean(GameSessionEntity.class));

    public void save(GameSessionEntity session) {
        table.putItem(session);
    }

    public GameSessionEntity get(String sessionId) {
        return table.getItem(r -> r.key(k -> k.partitionValue(sessionId)));
    }
}