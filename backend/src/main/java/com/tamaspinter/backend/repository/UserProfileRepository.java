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
import software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Repository
public class UserProfileRepository {
    public static final String LEADERBOARD_PARTITION = "global";
    private final DynamoDbTable<UserProfile> table;
    private final DynamoDbEnhancedClient enhancedClient;
    private final UsernameReservationRepository usernameRepository;
    private final DynamoDbClient dynamoClient;
    private final String tableName;

    public UserProfileRepository(
            DynamoDbEnhancedClient enhancedClient,
            UsernameReservationRepository usernameRepository,
            DynamoDbClient dynamoClient,
            @Value("${dynamodb.users.table}") String tableName) {
        this.enhancedClient = enhancedClient;
        this.usernameRepository = usernameRepository;
        this.dynamoClient = dynamoClient;
        this.tableName = tableName;
        this.table = enhancedClient.table(
                tableName,
                TableSchema.fromBean(UserProfile.class)
        );
    }

    /**
     * Saves profile fields with UpdateItem and never writes the admin block flag.
     */
    public void save(UserProfile user) {
        if (user.getLeaderboardPk() == null || user.getLeaderboardPk().isBlank()) {
            user.setLeaderboardPk(LEADERBOARD_PARTITION);
        }
        table.updateItem(UpdateItemEnhancedRequest.builder(UserProfile.class)
                .item(user.withoutBlockedFlag())
                .ignoreNulls(true)
                .build());
    }

    /**
     * Sets (blocked = true) or removes (blocked = false) the {@code blocked} attribute.
     *
     * @return false when no user row exists for the id
     */
    public boolean setBlocked(String userId, boolean blocked) {
        UpdateItemRequest.Builder request = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("user_id", AttributeValue.fromS(userId)))
                .updateExpression(blocked ? "SET #blocked = :blocked" : "REMOVE #blocked")
                .conditionExpression("attribute_exists(user_id)")
                .expressionAttributeNames(Map.of("#blocked", "blocked"));
        if (blocked) {
            request.expressionAttributeValues(Map.of(":blocked", AttributeValue.fromBool(true)));
        }
        try {
            dynamoClient.updateItem(request.build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    /** All rows of the users table, including username claim rows (callers filter those out). */
    public List<UserProfile> scanAll() {
        List<UserProfile> result = new ArrayList<>();
        table.scan().items().forEach(result::add);
        return result;
    }

    public UserProfile get(String userId) {
        return table.getItem(r -> r.key(k -> k.partitionValue(userId)));
    }

    public boolean updateUsernameIfAvailable(UserProfile profile, String username) {
        return usernameRepository.updateUsernameIfAvailable(profile, username);
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
