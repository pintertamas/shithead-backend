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
    private String avatarUrl;
    @Getter(AccessLevel.NONE)
    private double eloScore;
    @Getter(AccessLevel.NONE)
    private String leaderboardPk;

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
}
