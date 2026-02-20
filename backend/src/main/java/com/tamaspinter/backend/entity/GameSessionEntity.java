package com.tamaspinter.backend.entity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class GameSessionEntity {
    private String sessionId;
    private List<PlayerEntity> players;
    private List<CardEntity> discardPile;
    @Builder.Default
    private List<CardEntity> deck = new ArrayList<>();
    private String currentPlayerId;
    private boolean started;
    private boolean finished;
    private String shitheadId;
    @Getter(AccessLevel.NONE)
    private String ownerId;
    private GameConfigEntity config;
    @Getter(AccessLevel.NONE)
    private String createdAt;
    @Getter(AccessLevel.NONE)
    private Long ttl;

    @DynamoDbAttribute("user_id")
    public String getOwnerId() {
        return ownerId;
    }

    @DynamoDbPartitionKey
    @DynamoDbAttribute("game_id")
    public String getSessionId() {
        return sessionId;
    }

    @DynamoDbAttribute("created_at")
    public String getCreatedAt() {
        return createdAt;
    }

    @DynamoDbAttribute("ttl")
    public Long getTtl() {
        return ttl;
    }
}
