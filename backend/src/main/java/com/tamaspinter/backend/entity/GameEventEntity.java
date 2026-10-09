package com.tamaspinter.backend.entity;

import com.tamaspinter.backend.game.GameEventType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class GameEventEntity {
    private long seq;
    private GameEventType type;
    private String playerId;
    private String username;
    private List<CardEntity> cards;
    private int count;
    private long ts;
}
