package com.tamaspinter.backend.entity;

import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

import java.util.List;

@Getter
@Setter
@DynamoDbBean
public class PlayerEntity {
    private String playerId;
    private String username;
    private List<CardEntity> hand;
    private List<CardEntity> faceUp;
    private List<CardEntity> faceDown;
    private boolean out;

    @DynamoDbAttribute("playerId")
    public String getPlayerId() { return playerId; }
}
