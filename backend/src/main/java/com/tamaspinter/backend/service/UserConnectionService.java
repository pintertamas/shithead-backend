package com.tamaspinter.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiClient;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.DeleteConnectionRequest;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.GoneException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Closes a user's WebSocket connections. The connection registry is keyed by connection id only,
 * so the user's connections are found with a filtered scan.
 */
@Slf4j
@Service
public class UserConnectionService {

    private static final String CONNECTION_KEY = "connection_id";
    private static final String USER_ATTRIBUTE = "user_id";

    private final DynamoDbClient dynamoClient;
    private final String connectionsTable;
    private final String managementEndpoint;

    public UserConnectionService(
            DynamoDbClient dynamoClient,
            @Value("${ws.connections.table:}") String connectionsTable,
            @Value("${ws.management.endpoint:}") String managementEndpoint) {
        this.dynamoClient = dynamoClient;
        this.connectionsTable = connectionsTable;
        this.managementEndpoint = managementEndpoint;
    }

    /**
     * Best-effort: asks API Gateway to drop each connection and deletes the registry rows.
     *
     * @return number of connections API Gateway confirmed closing
     */
    public int disconnectUser(String userId) {
        if (connectionsTable == null || connectionsTable.isBlank()) {
            return 0;
        }
        List<String> connectionIds = findConnectionIds(userId);
        if (managementEndpoint == null || managementEndpoint.isBlank()) {
            connectionIds.forEach(this::deleteRecord);
            return 0;
        }
        int closed = 0;
        try (ApiGatewayManagementApiClient client = ApiGatewayManagementApiClient.builder()
                .endpointOverride(URI.create(managementEndpoint))
                .build()) {
            for (String connectionId : connectionIds) {
                if (closeConnection(client, connectionId)) {
                    closed++;
                }
                deleteRecord(connectionId);
            }
        }
        return closed;
    }

    private boolean closeConnection(ApiGatewayManagementApiClient client, String connectionId) {
        try {
            client.deleteConnection(DeleteConnectionRequest.builder().connectionId(connectionId).build());
            return true;
        } catch (GoneException e) {
            return false;
        } catch (SdkException e) {
            log.warn("Could not close WebSocket connection {} for a blocked user", connectionId, e);
            return false;
        }
    }

    private List<String> findConnectionIds(String userId) {
        List<String> connectionIds = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        boolean morePages = true;
        while (morePages) {
            ScanRequest.Builder request = ScanRequest.builder()
                    .tableName(connectionsTable)
                    .filterExpression("#uid = :uid")
                    .expressionAttributeNames(Map.of("#uid", USER_ATTRIBUTE))
                    .expressionAttributeValues(Map.of(":uid", AttributeValue.fromS(userId)));
            if (startKey != null) {
                request.exclusiveStartKey(startKey);
            }
            ScanResponse response = dynamoClient.scan(request.build());
            response.items().forEach(item -> connectionIds.add(item.get(CONNECTION_KEY).s()));
            startKey = response.hasLastEvaluatedKey() ? response.lastEvaluatedKey() : null;
            morePages = startKey != null && !startKey.isEmpty();
        }
        return connectionIds;
    }

    private void deleteRecord(String connectionId) {
        dynamoClient.deleteItem(DeleteItemRequest.builder()
                .tableName(connectionsTable)
                .key(Map.of(CONNECTION_KEY, AttributeValue.fromS(connectionId)))
                .build());
    }
}
