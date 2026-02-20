package com.tamaspinter.backend.entity;

import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.model.Suit;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class CardEntity {
    private Suit suit;
    private int value;
    private CardRule rule;
    private boolean alwaysPlayable;
}
