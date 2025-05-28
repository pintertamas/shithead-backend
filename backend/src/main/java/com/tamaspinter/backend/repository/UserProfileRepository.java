package com.tamaspinter.backend.repository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.*;
import com.tamaspinter.backend.model.*;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetResultPage;
import software.amazon.awssdk.enhanced.dynamodb.model.ReadBatch;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Repository
public class UserProfileRepository {

    private final DynamoDbTable<UserProfile> table;

    @Value("${dynamodb.users.table}")
    String tableName;

    private final DynamoDbEnhancedClient enhancedClient;

    public UserProfileRepository(
            DynamoDbEnhancedClient enhancedClient) {
        this.enhancedClient = enhancedClient;
        this.table = enhancedClient.table(
                tableName,
                TableSchema.fromBean(UserProfile.class)
        );
    }

    public void save(UserProfile user) {
        table.putItem(user);
    }

    public UserProfile get(String userId) {
        return table.getItem(r -> r.key(k -> k.partitionValue(userId)));
    }

    public List<UserProfile> batchGet(List<String> userIds) {
        // Build a single ReadBatch with one GetItem per userId
        ReadBatch.Builder<UserProfile> readBatchBuilder = ReadBatch
                .builder(UserProfile.class)
                .mappedTableResource(table);
        for (String id : userIds) {
            readBatchBuilder.addGetItem(r -> r.key(Key.builder().partitionValue(id).build()));
        }
        ReadBatch readBatch = readBatchBuilder.build();

        // Build and execute the batch-get request
        BatchGetItemEnhancedRequest batchRequest = BatchGetItemEnhancedRequest
                .builder()
                .addReadBatch(readBatch)
                .build();

        Iterator<BatchGetResultPage> pages =
                enhancedClient.batchGetItem(batchRequest).iterator();

        // Collect and return all retrieved items
        List<UserProfile> result = new ArrayList<>();
        pages.forEachRemaining(page -> result.addAll(page.resultsForTable(table)));
        return result;
    }
}