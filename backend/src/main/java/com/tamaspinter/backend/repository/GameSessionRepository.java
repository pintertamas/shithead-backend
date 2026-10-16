package com.tamaspinter.backend.repository;

import com.tamaspinter.backend.entity.GameSessionEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.time.Instant;
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

    /**
     * Writes the game and stamps its last-activity time. Every game write goes through here,
     * so the glue janitor can tell an idle game from one that was just saved.
     */
    public void save(GameSessionEntity session) {
        session.setUpdatedAt(Instant.now().getEpochSecond());
        table.putItem(session);
    }

    /**
     * Points a finished game at its rematch lobby, only if the pointer still holds {@code expected}
     * (null for "no pointer yet"), so two simultaneous callers cannot both win.
     *
     * @return true when this call set the pointer, false when another caller got there first
     */
    public boolean claimRematch(GameSessionEntity finished, String expected, String rematchSessionId) {
        finished.setRematchSessionId(rematchSessionId);
        finished.setUpdatedAt(Instant.now().getEpochSecond());
        Expression condition = expected == null
                ? Expression.builder().expression("attribute_not_exists(rematchSessionId)").build()
                : Expression.builder()
                        .expression("rematchSessionId = :expected")
                        .putExpressionValue(":expected", AttributeValue.fromS(expected))
                        .build();
        try {
            table.putItem(PutItemEnhancedRequest.builder(GameSessionEntity.class)
                    .item(finished)
                    .conditionExpression(condition)
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    public GameSessionEntity get(String sessionId) {
        return table.getItem(r -> r.key(k -> k.partitionValue(sessionId)));
    }

    public void delete(String sessionId) {
        table.deleteItem(r -> r.key(k -> k.partitionValue(sessionId)));
    }

    public List<GameSessionEntity> findAll() {
        List<GameSessionEntity> results = new ArrayList<>();
        table.scan().forEach(page -> results.addAll(page.items()));
        return results;
    }
}
