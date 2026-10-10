package com.tamaspinter.backend.repository;

import com.tamaspinter.backend.model.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserProfileRepositoryTest {

    private static final String USERS_TABLE = "users";

    private DynamoDbTable<UserProfile> table;
    private DynamoDbClient dynamo;
    private UserProfileRepository repository;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        DynamoDbEnhancedClient enhanced = mock(DynamoDbEnhancedClient.class);
        table = mock(DynamoDbTable.class);
        doReturn(table).when(enhanced).table(anyString(), any(TableSchema.class));
        dynamo = mock(DynamoDbClient.class);
        repository = new UserProfileRepository(enhanced, mock(UsernameReservationRepository.class), dynamo, USERS_TABLE);
    }

    @Test
    void save_neverWritesTheBlockFlag_andSkipsNullAttributes() {
        // Given: a stale in-memory profile that still carries blocked = true
        UserProfile profile = UserProfile.builder().userId("u1").username("Ann").eloScore(1100).build();
        profile.setBlocked(true);

        // When
        repository.save(profile);

        // Then: the UpdateItem ignores nulls and the copy it writes has no blocked value
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<UpdateItemEnhancedRequest> captor = ArgumentCaptor.forClass(UpdateItemEnhancedRequest.class);
        verify(table).updateItem(captor.capture());
        assertTrue(captor.getValue().ignoreNulls());
        assertNull(((UserProfile) captor.getValue().item()).getBlocked());
        assertEquals("Ann", ((UserProfile) captor.getValue().item()).getUsername());
    }

    @Test
    void setBlocked_true_setsAttribute_conditionalOnExistingUser() {
        // Given
        when(dynamo.updateItem(any(UpdateItemRequest.class))).thenReturn(UpdateItemResponse.builder().build());

        // When
        boolean updated = repository.setBlocked("u1", true);

        // Then
        assertTrue(updated);
        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo).updateItem(captor.capture());
        UpdateItemRequest request = captor.getValue();
        assertEquals(USERS_TABLE, request.tableName());
        assertEquals("u1", request.key().get("user_id").s());
        assertEquals("SET #blocked = :blocked", request.updateExpression());
        assertEquals("attribute_exists(user_id)", request.conditionExpression());
        assertTrue(request.expressionAttributeValues().get(":blocked").bool());
    }

    @Test
    void setBlocked_false_removesAttribute() {
        // Given
        when(dynamo.updateItem(any(UpdateItemRequest.class))).thenReturn(UpdateItemResponse.builder().build());

        // When
        repository.setBlocked("u1", false);

        // Then
        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo).updateItem(captor.capture());
        assertEquals("REMOVE #blocked", captor.getValue().updateExpression());
        assertFalse(captor.getValue().hasExpressionAttributeValues());
    }

    @Test
    void setBlocked_forMissingUser_returnsFalse() {
        // Given
        when(dynamo.updateItem(any(UpdateItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder().message("no such user").build());

        // When / Then
        assertFalse(repository.setBlocked("ghost", true));
    }

    @Test
    void deleteProfile_releasesNicknameClaim_beforeDeletingTheRow() {
        // Given
        UsernameReservationRepository usernames = mock(UsernameReservationRepository.class);
        UserProfile existing = UserProfile.builder().userId("u1").username("Ann").build();
        DynamoDbEnhancedClient enhanced = mock(DynamoDbEnhancedClient.class);
        DynamoDbTable<UserProfile> usersTable = mock(DynamoDbTable.class);
        doReturn(usersTable).when(enhanced).table(anyString(), any(TableSchema.class));
        doReturn(existing).when(usersTable).getItem(any(Consumer.class));
        UserProfileRepository repo = new UserProfileRepository(enhanced, usernames, dynamo, USERS_TABLE);

        // When
        repo.deleteProfile("u1");

        // Then
        InOrder order = inOrder(usernames, dynamo);
        order.verify(usernames).releaseNickname(existing);
        order.verify(dynamo).deleteItem(DeleteItemRequest.builder()
                .tableName(USERS_TABLE)
                .key(Map.of("user_id", AttributeValue.fromS("u1")))
                .build());
    }
}
