package com.tamaspinter.backend.model;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class UserProfile {
    private String userId;
    private String username;
    /**
     * Email from the Cognito token's {@code email} claim. Null until a token carrying the claim has
     * written the row. Read by the admin user list only.
     */
    private String email;
    private String avatarUrl;
    @Getter(AccessLevel.NONE)
    private double eloScore;
    @Getter(AccessLevel.NONE)
    private String leaderboardPk;
    @Getter(AccessLevel.NONE)
    private Boolean blocked;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("user_id")
    public String getUserId() {
        return userId;
    }

    @DynamoDbSecondarySortKey(indexNames = "leaderboard-index")
    @DynamoDbAttribute("elo_score")
    public double getEloScore() {
        return eloScore;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = "leaderboard-index")
    @DynamoDbAttribute("leaderboard_pk")
    public String getLeaderboardPk() {
        return leaderboardPk;
    }

    /**
     * Admin block flag. A missing attribute means not blocked; it is written only via UpdateItem
     * in {@code UserProfileRepository#setBlocked}, never by profile saves.
     */
    @DynamoDbAttribute("blocked")
    public Boolean getBlocked() {
        return blocked;
    }

    /**
     * Returns a copy without the block flag, so a full-item save can never overwrite it.
     * Used together with an UpdateItem that ignores null attributes.
     */
    public UserProfile withoutBlockedFlag() {
        return UserProfile.builder()
                .userId(userId)
                .username(username)
                .email(email)
                .avatarUrl(avatarUrl)
                .eloScore(eloScore)
                .leaderboardPk(leaderboardPk)
                .build();
    }
}
