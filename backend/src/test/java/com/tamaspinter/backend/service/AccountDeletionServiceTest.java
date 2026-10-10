package com.tamaspinter.backend.service;

import com.tamaspinter.backend.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class AccountDeletionServiceTest {

    private static final String USER_ID = "user-sub-1";
    private static final String COGNITO_USERNAME = "Google_1234567890";
    private static final String POOL_ID = "eu-central-1_pool";

    private LobbyMembershipService lobbies;
    private UserConnectionService connections;
    private UserProfileRepository userRepo;
    private CognitoIdentityProviderClient cognito;

    private AccountDeletionService service(String poolId) {
        return new AccountDeletionService(lobbies, connections, userRepo, cognito, poolId);
    }

    private void mockDependencies() {
        lobbies = mock(LobbyMembershipService.class);
        connections = mock(UserConnectionService.class);
        userRepo = mock(UserProfileRepository.class);
        cognito = mock(CognitoIdentityProviderClient.class);
    }

    @Test
    void deleteAccount_runsStepsInOrder_endingWithCognito() {
        mockDependencies();
        // Given
        AccountDeletionService service = service(POOL_ID);

        // When
        AccountDeletionService.DeletionOutcome outcome = service.deleteAccount(USER_ID, COGNITO_USERNAME);

        // Then
        assertEquals(AccountDeletionService.DeletionOutcome.DELETED, outcome);
        InOrder order = inOrder(lobbies, connections, userRepo, cognito);
        order.verify(lobbies).removeFromUnstartedLobbies(USER_ID);
        order.verify(connections).disconnectUser(USER_ID);
        order.verify(userRepo).deleteProfile(USER_ID);
        order.verify(cognito).adminDeleteUser(AdminDeleteUserRequest.builder()
                .userPoolId(POOL_ID)
                .username(COGNITO_USERNAME)
                .build());
    }

    @Test
    void deleteAccount_cognitoFailure_returnsCognitoFailed() {
        mockDependencies();
        // Given
        doThrow(CognitoIdentityProviderException.builder().message("throttled").build())
                .when(cognito).adminDeleteUser(any(AdminDeleteUserRequest.class));
        AccountDeletionService service = service(POOL_ID);

        // When
        AccountDeletionService.DeletionOutcome outcome = service.deleteAccount(USER_ID, COGNITO_USERNAME);

        // Then
        assertEquals(AccountDeletionService.DeletionOutcome.COGNITO_FAILED, outcome);
        verify(userRepo).deleteProfile(USER_ID);
    }

    @Test
    void deleteAccount_lobbyCleanupFails_stillDeletesProfileAndCognitoUser() {
        mockDependencies();
        // Given
        when(lobbies.removeFromUnstartedLobbies(USER_ID)).thenThrow(SdkClientException.create("network down"));
        AccountDeletionService service = service(POOL_ID);

        // When
        AccountDeletionService.DeletionOutcome outcome = service.deleteAccount(USER_ID, COGNITO_USERNAME);

        // Then
        assertEquals(AccountDeletionService.DeletionOutcome.DELETED, outcome);
        verify(userRepo).deleteProfile(USER_ID);
        verify(cognito).adminDeleteUser(any(AdminDeleteUserRequest.class));
    }

    @Test
    void deleteAccount_connectionCleanupAndProfileDeleteFail_stillDeletesCognitoUser() {
        mockDependencies();
        // Given
        when(connections.disconnectUser(USER_ID)).thenThrow(SdkClientException.create("scan failed"));
        doThrow(SdkClientException.create("dynamo down")).when(userRepo).deleteProfile(USER_ID);
        AccountDeletionService service = service(POOL_ID);

        // When
        AccountDeletionService.DeletionOutcome outcome = service.deleteAccount(USER_ID, COGNITO_USERNAME);

        // Then
        assertEquals(AccountDeletionService.DeletionOutcome.DELETED, outcome);
        verify(cognito).adminDeleteUser(any(AdminDeleteUserRequest.class));
    }

    @Test
    void deleteAccount_cognitoUserAlreadyGone_countsAsDeleted() {
        mockDependencies();
        // Given
        doThrow(UserNotFoundException.builder().message("User does not exist.").build())
                .when(cognito).adminDeleteUser(any(AdminDeleteUserRequest.class));
        AccountDeletionService service = service(POOL_ID);

        // When
        AccountDeletionService.DeletionOutcome outcome = service.deleteAccount(USER_ID, COGNITO_USERNAME);

        // Then
        assertEquals(AccountDeletionService.DeletionOutcome.DELETED, outcome);
    }

    @Test
    void deleteAccount_withoutPoolId_failsBeforeCallingCognito() {
        mockDependencies();
        // Given
        AccountDeletionService service = service("");

        // When
        AccountDeletionService.DeletionOutcome outcome = service.deleteAccount(USER_ID, COGNITO_USERNAME);

        // Then
        assertEquals(AccountDeletionService.DeletionOutcome.COGNITO_FAILED, outcome);
        verify(cognito, never()).adminDeleteUser(any(AdminDeleteUserRequest.class));
    }
}
