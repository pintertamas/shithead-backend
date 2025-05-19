package com.tamaspinter.backend.entity;

import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.util.List;

@Getter
@Setter
@DynamoDbBean
public class GameSessionEntity {
    private String sessionId;
    private List<PlayerEntity> players;
    private List<CardEntity> discardPile;
    private String currentPlayerId;
    private boolean started;

    @DynamoDbPartitionKey
    public String getSessionId() { return sessionId; }
}