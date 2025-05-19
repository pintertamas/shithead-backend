package com.tamaspinter.backend.entity;

import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.model.Suit;
import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

@Getter
@Setter
@DynamoDbBean
public class CardEntity {
    private Suit suit;
    private int value;
    private CardRule rule;
    private boolean alwaysPlayable;
}