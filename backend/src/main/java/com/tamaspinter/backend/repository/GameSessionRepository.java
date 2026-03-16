package com.tamaspinter.backend.repository;

import com.tamaspinter.backend.entity.GameSessionEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.util.ArrayList;
import java.util.List;

@Repository
public class GameSessionRepository {
    private final DynamoDbTable<GameSessionEntity> table;
    private final DynamoDbIndex<GameSessionEntity> ownerIndex;

    public GameSessionRepository(
            DynamoDbEnhancedClient enhancedClient,
            @Value("${dynamodb.sessions.table}") String tableName) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(GameSessionEntity.class));
        this.ownerIndex = this.table.index("user_id-index");
    }

    public List<GameSessionEntity> findByOwnerId(String ownerId) {
        List<GameSessionEntity> results = new ArrayList<>();
        ownerIndex.query(QueryConditional.keyEqualTo(
                Key.builder().partitionValue(ownerId).build()
        )).forEach(page -> results.addAll(page.items()));
        return results;
    }

    public void save(GameSessionEntity session) {
        table.putItem(session);
    }

    public GameSessionEntity get(String sessionId) {
        return table.getItem(r -> r.key(k -> k.partitionValue(sessionId)));
    }

    public void delete(String sessionId) {
        table.deleteItem(r -> r.key(k -> k.partitionValue(sessionId)));
    }
}
