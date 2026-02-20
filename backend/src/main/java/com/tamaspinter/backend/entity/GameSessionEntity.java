package com.tamaspinter.backend.entity;

import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
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
    private List<CardEntity> deck = new java.util.ArrayList<>();
    private String currentPlayerId;
    private boolean started;
    private boolean finished;
    private String shitheadId;
    @lombok.Getter(lombok.AccessLevel.NONE)
    private String ownerId;
    private GameConfigEntity config;

    @DynamoDbAttribute("user_id")
    public String getOwnerId() { return ownerId; }

    @DynamoDbPartitionKey
    @DynamoDbAttribute("game_id")
    public String getSessionId() { return sessionId; }
}