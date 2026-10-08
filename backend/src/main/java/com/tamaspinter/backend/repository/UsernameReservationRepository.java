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
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Repository
public class UsernameReservationRepository {
    private static final String USERNAME_CLAIM_PREFIX = "__username__#";
    private static final String CLAIM_OWNER_ATTRIBUTE = "owner_user_id";

    private final DynamoDbTable<UserProfile> table;
    private final DynamoDbClient dynamoClient;
    private final String tableName;

    public UsernameReservationRepository(
            DynamoDbEnhancedClient enhancedClient,
            DynamoDbClient dynamoClient,
            @Value("${dynamodb.users.table}") String tableName) {
        this.dynamoClient = dynamoClient;
        this.tableName = tableName;
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(UserProfile.class));
    }

    public boolean updateUsernameIfAvailable(UserProfile profile, String username) {
        String userId = profile.getUserId();
        String currentUsername = profile.getUsername();
        String normalizedUsername = normalizeUsername(username);
        String normalizedCurrentUsername = currentUsername == null ? null : normalizeUsername(currentUsername);
        if (normalizedUsername.equals(normalizedCurrentUsername)) {
            profile.setUsername(username);
            table.putItem(profile);
            return true;
        }
        if (usernameInUse(normalizedUsername, userId)) {
            return false;
        }
        try {
            reserveNickname(profile, username, normalizedUsername, normalizedCurrentUsername);
            return true;
        } catch (TransactionCanceledException e) {
            return resolveCancelledNicknameUpdate(profile, normalizedUsername, userId, e);
        }
    }

    private void reserveNickname(
            UserProfile profile, String username, String normalizedUsername, String normalizedCurrentUsername) {
        List<TransactWriteItem> transaction = buildNicknameTransaction(
                profile, username, normalizedUsername, normalizedCurrentUsername);
        dynamoClient.transactWriteItems(TransactWriteItemsRequest.builder()
                .transactItems(transaction)
                .build());
        profile.setUsername(username);
    }

    private List<TransactWriteItem> buildNicknameTransaction(
            UserProfile profile, String username, String normalizedUsername, String normalizedCurrentUsername) {
        String userId = profile.getUserId();
        String newClaimId = claimId(normalizedUsername);
        List<TransactWriteItem> transaction = new ArrayList<>();
        transaction.add(buildClaimPut(newClaimId, userId));
        transaction.add(buildUsernameUpdate(userId, profile.getUsername(), username));
        addOldClaimDelete(transaction, normalizedCurrentUsername, newClaimId, userId);
        return transaction;
    }

    private TransactWriteItem buildClaimPut(String claimId, String userId) {
        return TransactWriteItem.builder().put(Put.builder()
                .tableName(tableName)
                .item(Map.of(
                        "user_id", AttributeValue.fromS(claimId),
                        CLAIM_OWNER_ATTRIBUTE, AttributeValue.fromS(userId)))
                .conditionExpression("attribute_not_exists(user_id) OR #owner = :owner")
                .expressionAttributeNames(Map.of("#owner", CLAIM_OWNER_ATTRIBUTE))
                .expressionAttributeValues(Map.of(":owner", AttributeValue.fromS(userId)))
                .build()).build();
    }

    private TransactWriteItem buildUsernameUpdate(String userId, String currentUsername, String username) {
        Update.Builder update = Update.builder()
                .tableName(tableName)
                .key(Map.of("user_id", AttributeValue.fromS(userId)))
                .updateExpression("SET #username = :newUsername")
                .expressionAttributeNames(Map.of("#username", "username"))
                .expressionAttributeValues(Map.of(":newUsername", AttributeValue.fromS(username)));
        if (currentUsername == null) {
            update.conditionExpression("attribute_not_exists(#username)");
        } else {
            update.conditionExpression("#username = :currentUsername")
                    .expressionAttributeValues(Map.of(
                            ":newUsername", AttributeValue.fromS(username),
                            ":currentUsername", AttributeValue.fromS(currentUsername)));
        }
        return TransactWriteItem.builder().update(update.build()).build();
    }

    private void addOldClaimDelete(
            List<TransactWriteItem> transaction, String normalizedCurrentUsername, String newClaimId, String userId) {
        if (normalizedCurrentUsername == null) {
            return;
        }
        String oldClaimId = claimId(normalizedCurrentUsername);
        if (!newClaimId.equals(oldClaimId) && userOwnsClaim(oldClaimId, userId)) {
            transaction.add(buildClaimDelete(oldClaimId, userId));
        }
    }

    private TransactWriteItem buildClaimDelete(String claimId, String userId) {
        return TransactWriteItem.builder().delete(Delete.builder()
                .tableName(tableName)
                .key(Map.of("user_id", AttributeValue.fromS(claimId)))
                .conditionExpression("#owner = :owner")
                .expressionAttributeNames(Map.of("#owner", CLAIM_OWNER_ATTRIBUTE))
                .expressionAttributeValues(Map.of(":owner", AttributeValue.fromS(userId)))
                .build()).build();
    }

    private boolean resolveCancelledNicknameUpdate(
            UserProfile profile, String normalizedUsername, String userId, TransactionCanceledException error) {
        String claimOwner = getClaimOwner(claimId(normalizedUsername));
        if (usernameInUse(normalizedUsername, userId)
                || claimOwner != null && !claimOwner.equals(userId)) {
            return false;
        }
        UserProfile latest = table.getItem(r -> r.key(k -> k.partitionValue(userId)));
        if (latest != null && latest.getUsername() != null
                && normalizeUsername(latest.getUsername()).equals(normalizedUsername)) {
            profile.setUsername(latest.getUsername());
            return true;
        }
        throw error;
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
}
