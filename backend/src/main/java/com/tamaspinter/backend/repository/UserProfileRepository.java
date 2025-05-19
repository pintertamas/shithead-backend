package com.tamaspinter.backend.repository;

import software.amazon.awssdk.enhanced.dynamodb.*;
import com.tamaspinter.backend.model.*;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetResultPage;
import software.amazon.awssdk.enhanced.dynamodb.model.ReadBatch;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class UserProfileRepository {
    private final DynamoDbTable<UserProfile> table =
            DynamoDbClientProvider.client()
                    .table("Users", TableSchema.fromBean(UserProfile.class));

    public void save(UserProfile u) {
        table.putItem(u);
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
                DynamoDbClientProvider.client().batchGetItem(batchRequest).iterator();

        // Collect and return all retrieved items
        List<UserProfile> result = new ArrayList<>();
        while (pages.hasNext()) {
            BatchGetResultPage page = pages.next();
            result.addAll(page.resultsForTable(table));
        }
        return result;
    }
}