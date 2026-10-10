package com.tamaspinter.backend.service;

import com.tamaspinter.backend.repository.UserProfileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

/**
 * Deletes a user's own account: unstarted lobby seats, WebSocket connections, the profile row (with
 * its Elo rating and nickname claim) and finally the Cognito sign-in user.
 *
 * <p>Lobby, connection and profile cleanup are best-effort and only logged on failure. The Cognito
 * deletion decides the outcome, so a failed Cognito call is the only way the account stays.
 */
@Slf4j
@Service
public class AccountDeletionService {

    public enum DeletionOutcome { DELETED, COGNITO_FAILED }

    private final LobbyMembershipService lobbies;
    private final UserConnectionService connections;
    private final UserProfileRepository userRepo;
    private final CognitoIdentityProviderClient cognito;
    private final String userPoolId;

    public AccountDeletionService(
            LobbyMembershipService lobbies,
            UserConnectionService connections,
            UserProfileRepository userRepo,
            CognitoIdentityProviderClient cognito,
            @Value("${cognito.user-pool-id:}") String userPoolId) {
        this.lobbies = lobbies;
        this.connections = connections;
        this.userRepo = userRepo;
        this.cognito = cognito;
        this.userPoolId = userPoolId;
    }

    /**
     * Deletes the account of the given user. Both arguments must come from the verified token,
     * never from the request body.
     */
    public DeletionOutcome deleteAccount(String userId, String cognitoUsername) {
        removeFromLobbiesQuietly(userId);
        disconnectQuietly(userId);
        deleteProfileQuietly(userId);
        return deleteCognitoUser(cognitoUsername);
    }

    private void removeFromLobbiesQuietly(String userId) {
        try {
            int removed = lobbies.removeFromUnstartedLobbies(userId);
            log.info("Account deletion removed a user from {} unstarted lobbies", removed);
        } catch (SdkException e) {
            log.warn("Account deletion could not remove the user from lobbies", e);
        }
    }

    private void disconnectQuietly(String userId) {
        try {
            connections.disconnectUser(userId);
        } catch (SdkException e) {
            log.warn("Account deletion could not clean up WebSocket connections", e);
        }
    }

    private void deleteProfileQuietly(String userId) {
        try {
            userRepo.deleteProfile(userId);
        } catch (SdkException e) {
            log.error("Account deletion could not delete the profile row", e);
        }
    }

    private DeletionOutcome deleteCognitoUser(String cognitoUsername) {
        if (userPoolId == null || userPoolId.isBlank()) {
            log.error("COGNITO_USER_POOL_ID is not set; account deletion cannot reach Cognito");
            return DeletionOutcome.COGNITO_FAILED;
        }
        try {
            cognito.adminDeleteUser(AdminDeleteUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(cognitoUsername)
                    .build());
            log.info("Account deletion removed the Cognito user");
            return DeletionOutcome.DELETED;
        } catch (UserNotFoundException e) {
            log.info("Cognito user was already gone; treating account deletion as complete");
            return DeletionOutcome.DELETED;
        } catch (SdkException e) {
            log.error("Account deletion failed at Cognito", e);
            return DeletionOutcome.COGNITO_FAILED;
        }
    }
}
