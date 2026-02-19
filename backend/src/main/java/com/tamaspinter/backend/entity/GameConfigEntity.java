package com.tamaspinter.backend.entity;

import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@DynamoDbBean
public class GameConfigEntity {
    private int burnCount = 4;
    private int faceDownCount = 3;
    private int faceUpCount = 3;
    private int handCount = 3;
    // Maps card value (as string) to rule name, e.g. "2" -> "JOKER"
    private Map<String, String> cardRules = new HashMap<>();
    // Card values that can always be played regardless of the top card
    private List<Integer> alwaysPlayable = new ArrayList<>();
    // Card values that let the same player go again after playing
    private List<Integer> canPlayAgain = new ArrayList<>();
}
