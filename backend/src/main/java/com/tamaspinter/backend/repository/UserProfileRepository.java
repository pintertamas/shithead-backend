package com.tamaspinter.backend.repository;

import com.tamaspinter.backend.model.UserProfile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetResultPage;
import software.amazon.awssdk.enhanced.dynamodb.model.ReadBatch;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;

@Repository
public class UserProfileRepository {
    public static final String LEADERBOARD_PARTITION = "global";
    private static final String USERNAME_CLAIM_PREFIX = "__username__#";
    private static final String CLAIM_OWNER_ATTRIBUTE = "owner_user_id";

    private final DynamoDbTable<UserProfile> table;

    private final DynamoDbEnhancedClient enhancedClient;
    private final DynamoDbClient dynamoClient;
    private final String tableName;

    public UserProfileRepository(
            DynamoDbEnhancedClient enhancedClient,
            DynamoDbClient dynamoClient,
            @Value("${dynamodb.users.table}") String tableName) {
        this.enhancedClient = enhancedClient;
        this.dynamoClient = dynamoClient;
        this.tableName = tableName;
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

    /**
     * Atomically reserves a normalized nickname and updates the user's profile.
     * The profile scan also protects names written before reservation records existed.
     */
    public boolean updateUsernameIfAvailable(UserProfile profile, String username) {
        String userId = profile.getUserId();
        String currentUsername = profile.getUsername();
        String normalizedUsername = normalizeUsername(username);
        String normalizedCurrentUsername = currentUsername == null ? null : normalizeUsername(currentUsername);
        if (normalizedUsername.equals(normalizedCurrentUsername)) {
            profile.setUsername(username);
            save(profile);
            return true;
        }
        if (usernameInUse(normalizedUsername, userId)) {
            return false;
        }

        String newClaimId = claimId(normalizedUsername);
        List<TransactWriteItem> transaction = new ArrayList<>();
        transaction.add(TransactWriteItem.builder().put(Put.builder()
                .tableName(tableName)
                .item(Map.of(
                        "user_id", AttributeValue.fromS(newClaimId),
                        CLAIM_OWNER_ATTRIBUTE, AttributeValue.fromS(userId)))
                .conditionExpression("attribute_not_exists(user_id) OR #owner = :owner")
                .expressionAttributeNames(Map.of("#owner", CLAIM_OWNER_ATTRIBUTE))
                .expressionAttributeValues(Map.of(":owner", AttributeValue.fromS(userId)))
                .build()).build());

        Update.Builder profileUpdate = Update.builder()
                .tableName(tableName)
                .key(Map.of("user_id", AttributeValue.fromS(userId)))
                .updateExpression("SET #username = :newUsername")
                .expressionAttributeNames(Map.of("#username", "username"))
                .expressionAttributeValues(Map.of(":newUsername", AttributeValue.fromS(username)));
        if (currentUsername == null) {
            profileUpdate.conditionExpression("attribute_not_exists(#username)");
        } else {
            profileUpdate.conditionExpression("#username = :currentUsername")
                    .expressionAttributeValues(Map.of(
                            ":newUsername", AttributeValue.fromS(username),
                            ":currentUsername", AttributeValue.fromS(currentUsername)));
        }
        transaction.add(TransactWriteItem.builder().update(profileUpdate.build()).build());

        String oldClaimId = normalizedCurrentUsername == null ? null : claimId(normalizedCurrentUsername);
        if (oldClaimId != null && !newClaimId.equals(oldClaimId) && userOwnsClaim(oldClaimId, userId)) {
            transaction.add(TransactWriteItem.builder().delete(Delete.builder()
                    .tableName(tableName)
                    .key(Map.of("user_id", AttributeValue.fromS(oldClaimId)))
                    .conditionExpression("#owner = :owner")
                    .expressionAttributeNames(Map.of("#owner", CLAIM_OWNER_ATTRIBUTE))
                    .expressionAttributeValues(Map.of(":owner", AttributeValue.fromS(userId)))
                    .build()).build());
        }

        try {
            dynamoClient.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(transaction)
                    .build());
            profile.setUsername(username);
            return true;
        } catch (TransactionCanceledException e) {
            String claimOwner = getClaimOwner(newClaimId);
            if (usernameInUse(normalizedUsername, userId)
                    || claimOwner != null && !claimOwner.equals(userId)) {
                return false;
            }
            UserProfile latest = get(userId);
            if (latest != null && latest.getUsername() != null
                    && normalizeUsername(latest.getUsername()).equals(normalizedUsername)) {
                profile.setUsername(latest.getUsername());
                return true;
            }
            throw e;
        }
    }

    private boolean usernameInUse(String normalizedUsername, String excludingUserId) {
        return table.scan(ScanEnhancedRequest.builder().consistentRead(true).build())
                .items()
                .stream()
                .anyMatch(existing -> existing.getUserId() != null
                        && !existing.getUserId().equals(excludingUserId)
                        && existing.getUsername() != null
                        && normalizeUsername(existing.getUsername()).equals(normalizedUsername));
    }

    private boolean userOwnsClaim(String claimId, String userId) {
        return userId.equals(getClaimOwner(claimId));
    }

    private String getClaimOwner(String claimId) {
        Map<String, AttributeValue> item = dynamoClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("user_id", AttributeValue.fromS(claimId)))
                .consistentRead(true)
                .build()).item();
        return item == null || item.get(CLAIM_OWNER_ATTRIBUTE) == null
                ? null : item.get(CLAIM_OWNER_ATTRIBUTE).s();
    }

    private static String claimId(String normalizedUsername) {
        return USERNAME_CLAIM_PREFIX + normalizedUsername;
    }

    private static String normalizeUsername(String username) {
        return Normalizer.normalize(username.trim(), Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
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
