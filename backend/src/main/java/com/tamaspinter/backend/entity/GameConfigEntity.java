package com.tamaspinter.backend.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class GameConfigEntity {
    @Builder.Default
    private int burnCount = 4;
    @Builder.Default
    private int faceDownCount = 3;
    @Builder.Default
    private int faceUpCount = 3;
    @Builder.Default
    private int handCount = 3;
    @Builder.Default
    private Map<String, String> cardRules = new HashMap<>();
    @Builder.Default
    private List<Integer> alwaysPlayable = new ArrayList<>();
    @Builder.Default
    private List<Integer> canPlayAgain = new ArrayList<>();
}
