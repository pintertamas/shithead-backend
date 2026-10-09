package com.tamaspinter.backend.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserConnectionServiceTest {

    private static final String TABLE = "ws-connections";

    @Test
    void disconnectUser_withoutManagementEndpoint_deletesOnlyRegistryRows() {
        // Given: two connections belong to the blocked user
        DynamoDbClient dynamo = mock(DynamoDbClient.class);
        when(dynamo.scan(any(ScanRequest.class))).thenReturn(ScanResponse.builder()
                .items(List.of(connection("c1"), connection("c2")))
                .build());
        UserConnectionService service = new UserConnectionService(dynamo, TABLE, "");

        // When
        int closed = service.disconnectUser("player-7");

        // Then
        assertEquals(0, closed);
        ArgumentCaptor<DeleteItemRequest> deletes = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(dynamo, times(2)).deleteItem(deletes.capture());
        assertEquals("c1", deletes.getAllValues().get(0).key().get("connection_id").s());
        assertEquals("c2", deletes.getAllValues().get(1).key().get("connection_id").s());
    }

    @Test
    void disconnectUser_filtersScanByUserId_andFollowsPagination() {
        // Given: the first page has a continuation key, the second page is the last
        DynamoDbClient dynamo = mock(DynamoDbClient.class);
        Map<String, AttributeValue> cursor = Map.of("connection_id", AttributeValue.fromS("c1"));
        when(dynamo.scan(any(ScanRequest.class)))
                .thenReturn(ScanResponse.builder().items(List.of(connection("c1"))).lastEvaluatedKey(cursor).build())
                .thenReturn(ScanResponse.builder().items(List.of(connection("c2"))).build());
        UserConnectionService service = new UserConnectionService(dynamo, TABLE, "");

        // When
        service.disconnectUser("player-7");

        // Then
        ArgumentCaptor<ScanRequest> scans = ArgumentCaptor.forClass(ScanRequest.class);
        verify(dynamo, times(2)).scan(scans.capture());
        assertEquals("#uid = :uid", scans.getAllValues().get(0).filterExpression());
        assertEquals("player-7", scans.getAllValues().get(0).expressionAttributeValues().get(":uid").s());
        assertEquals(cursor, scans.getAllValues().get(1).exclusiveStartKey());
        verify(dynamo, times(2)).deleteItem(any(DeleteItemRequest.class));
    }

    @Test
    void disconnectUser_withoutConnectionsTable_doesNothing() {
        // Given
        DynamoDbClient dynamo = mock(DynamoDbClient.class);
        UserConnectionService service = new UserConnectionService(dynamo, "", "");

        // When / Then
        assertEquals(0, service.disconnectUser("player-7"));
        verify(dynamo, times(0)).scan(any(ScanRequest.class));
    }

    private static Map<String, AttributeValue> connection(String id) {
        return Map.of(
                "connection_id", AttributeValue.fromS(id),
                "user_id", AttributeValue.fromS("player-7"));
    }
}
