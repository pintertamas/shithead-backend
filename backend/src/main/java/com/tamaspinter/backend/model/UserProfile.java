package com.tamaspinter.backend.model;

import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

@Getter
@Setter
@DynamoDbBean
public class UserProfile {
    private String userId;
    private String username;
    private String avatarUrl;
    private double eloScore;

    @DynamoDbPartitionKey
    public String getUserId() { return userId; }
}