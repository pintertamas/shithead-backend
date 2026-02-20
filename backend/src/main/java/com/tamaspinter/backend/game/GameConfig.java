package com.tamaspinter.backend.game;

import com.tamaspinter.backend.entity.GameConfigEntity;
import com.tamaspinter.backend.model.CardRule;
import lombok.Builder;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Getter
@Builder
public class GameConfig {
    private final int faceDownCount;
    private final int faceUpCount;
    private final int handCount;
    private final int burnCount;
    @Builder.Default
    private final Map<Integer, CardRule> cardRuleMap = new HashMap<>();
    @Builder.Default
    private final Map<Integer, Boolean> alwaysPlayableMap = new HashMap<>();
    @Builder.Default
    private final Map<Integer, Boolean> canPlayAgainMap = new HashMap<>();

    public static GameConfig defaultGameConfig() {
        GameConfig config = GameConfig.builder()
                .faceDownCount(3)
                .faceUpCount(3)
                .handCount(3)
                .burnCount(4)
                .build();
        config.cardRuleMap.put(2, CardRule.JOKER);
        config.cardRuleMap.put(6, CardRule.SMALLER);
        config.cardRuleMap.put(8, CardRule.TRANSPARENT);
        config.cardRuleMap.put(9, CardRule.REVERSE);
        config.cardRuleMap.put(10, CardRule.BURNER);
        config.alwaysPlayableMap.put(2, true);
        config.alwaysPlayableMap.put(8, true);
        config.canPlayAgainMap.put(10, true);
        return config;
    }

    public static GameConfig fromEntity(GameConfigEntity e) {
        GameConfig config = GameConfig.builder()
                .faceDownCount(e.getFaceDownCount())
                .faceUpCount(e.getFaceUpCount())
                .handCount(e.getHandCount())
                .burnCount(e.getBurnCount())
                .build();
        e.getCardRules().forEach((key, value) ->
                config.cardRuleMap.put(Integer.parseInt(key), CardRule.valueOf(value)));
        e.getAlwaysPlayable().forEach(value -> config.alwaysPlayableMap.put(value, true));
        e.getCanPlayAgain().forEach(value -> config.canPlayAgainMap.put(value, true));
        return config;
    }

    public GameConfigEntity toEntity() {
        Map<String, String> rules = new HashMap<>();
        this.cardRuleMap.forEach((key, value) -> rules.put(String.valueOf(key), value.name()));
        return GameConfigEntity.builder()
                .burnCount(this.burnCount)
                .faceDownCount(this.faceDownCount)
                .faceUpCount(this.faceUpCount)
                .handCount(this.handCount)
                .cardRules(rules)
                .alwaysPlayable(this.alwaysPlayableMap.entrySet().stream()
                        .filter(Map.Entry::getValue).map(Map.Entry::getKey).collect(Collectors.toList()))
                .canPlayAgain(this.canPlayAgainMap.entrySet().stream()
                        .filter(Map.Entry::getValue).map(Map.Entry::getKey).collect(Collectors.toList()))
                .build();
    }

    public CardRule getCardRule(int value) {
        return cardRuleMap.getOrDefault(value, CardRule.DEFAULT);
    }

    public boolean isAlwaysPlayable(int value) {
        return alwaysPlayableMap.getOrDefault(value, false);
    }

    public boolean canPlayAgain(int value) {
        return canPlayAgainMap.getOrDefault(value, false);
    }
}
