package com.tamaspinter.backend.repository;

import com.tamaspinter.backend.model.UserProfile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetResultPage;
import software.amazon.awssdk.enhanced.dynamodb.model.ReadBatch;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Repository
public class UserProfileRepository {
    public static final String LEADERBOARD_PARTITION = "global";

    private final DynamoDbTable<UserProfile> table;

    private final DynamoDbEnhancedClient enhancedClient;

    public UserProfileRepository(
            DynamoDbEnhancedClient enhancedClient,
            @Value("${dynamodb.users.table}") String tableName) {
        this.enhancedClient = enhancedClient;
        this.table = enhancedClient.table(
                tableName,
                TableSchema.fromBean(UserProfile.class)
        );
    }

    public void save(UserProfile user) {
        if (user.getLeaderboardPk() == null || user.getLeaderboardPk().isBlank()) {
            user.setLeaderboardPk(LEADERBOARD_PARTITION);
        }
        table.putItem(user);
    }

    public UserProfile get(String userId) {
        return table.getItem(r -> r.key(k -> k.partitionValue(userId)));
    }

    public List<UserProfile> batchGet(List<String> userIds) {
        ReadBatch.Builder<UserProfile> readBatchBuilder = ReadBatch
                .builder(UserProfile.class)
                .mappedTableResource(table);
        for (String id : userIds) {
            readBatchBuilder.addGetItem(r -> r.key(Key.builder().partitionValue(id).build()));
        }
        ReadBatch readBatch = readBatchBuilder.build();

        BatchGetItemEnhancedRequest batchRequest = BatchGetItemEnhancedRequest
                .builder()
                .addReadBatch(readBatch)
                .build();

        Iterator<BatchGetResultPage> pages =
                enhancedClient.batchGetItem(batchRequest).iterator();

        List<UserProfile> result = new ArrayList<>();
        pages.forEachRemaining(page -> result.addAll(page.resultsForTable(table)));
        return result;
    }

    public List<UserProfile> topLeaderboard(int limit) {
        DynamoDbIndex<UserProfile> leaderboardIndex = table.index("leaderboard-index");
        QueryConditional condition = QueryConditional.keyEqualTo(k -> k.partitionValue(LEADERBOARD_PARTITION));
        SdkIterable<Page<UserProfile>> pages = leaderboardIndex.query(r -> r.queryConditional(condition)
                .scanIndexForward(false)
                .limit(limit));

        List<UserProfile> result = new ArrayList<>();
        pages.stream().forEach(page -> result.addAll(page.items()));
        return result;
    }
}
