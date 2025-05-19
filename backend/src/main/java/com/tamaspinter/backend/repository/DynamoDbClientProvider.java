package com.tamaspinter.backend.repository;

import software.amazon.awssdk.enhanced.dynamodb.*;
import software.amazon.awssdk.services.dynamodb.*;

public class DynamoDbClientProvider {
    private static final DynamoDbEnhancedClient CLIENT =
            DynamoDbEnhancedClient.builder()
                    .dynamoDbClient(DynamoDbClient.create())
                    .build();

    public static DynamoDbEnhancedClient client() {
        return CLIENT;
    }
}