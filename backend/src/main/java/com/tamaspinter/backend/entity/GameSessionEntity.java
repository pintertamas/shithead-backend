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
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
@SuppressWarnings("PMD.TooManyFields")
public class GameSessionEntity {
    private String sessionId;
    private List<PlayerEntity> players;
    private List<CardEntity> discardPile;
    @Builder.Default
    private List<CardEntity> deck = new ArrayList<>();
    private String currentPlayerId;
    private boolean started;
    private boolean starting;
    @Builder.Default
    private boolean setupComplete = true;
    private boolean finished;
    private boolean eloUpdated;
    /** Per-player rating change of the Elo update. Null for games finished before this was recorded. */
    private Map<String, EloChangeEntity> eloChanges;
    private String shitheadId;
    @Getter(AccessLevel.NONE)
    private String ownerId;
    private GameConfigEntity config;
    private List<GameEventEntity> events;
    @Getter(AccessLevel.NONE)
    private String createdAt;
    @Getter(AccessLevel.NONE)
    private Long ttl;
    @Getter(AccessLevel.NONE)
    private Long updatedAt;

    @DynamoDbAttribute("user_id")
    @DynamoDbSecondaryPartitionKey(indexNames = "user_id-index")
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

    /** Epoch seconds of the last save, stamped by GameSessionRepository.save. Missing on games saved before the janitor. */
    @DynamoDbAttribute("updated_at")
    public Long getUpdatedAt() {
        return updatedAt;
    }
}
