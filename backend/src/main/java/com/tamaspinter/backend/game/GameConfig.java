package com.tamaspinter.backend.game;

import com.tamaspinter.backend.entity.GameConfigEntity;
import com.tamaspinter.backend.model.CardRule;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class GameConfig {
    @Getter private int faceDownCount;
    @Getter private int faceUpCount;
    @Getter private int handCount;
    @Getter private int burnCount;
    private final Map<Integer, CardRule> cardRuleMap = new HashMap<>();
    private final Map<Integer, Boolean> alwaysPlayableMap = new HashMap<>();
    private final Map<Integer, Boolean> canPlayAgainMap = new HashMap<>();

    public static GameConfig defaultGameConfig() {
        GameConfig config = new GameConfig();
        config.faceDownCount = 3;
        config.faceUpCount = 3;
        config.handCount = 3;
        config.burnCount = 4;
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
        GameConfig config = new GameConfig();
        config.faceDownCount = e.getFaceDownCount();
        config.faceUpCount = e.getFaceUpCount();
        config.handCount = e.getHandCount();
        config.burnCount = e.getBurnCount();
        e.getCardRules().forEach((k, v) ->
                config.cardRuleMap.put(Integer.parseInt(k), CardRule.valueOf(v)));
        e.getAlwaysPlayable().forEach(v -> config.alwaysPlayableMap.put(v, true));
        e.getCanPlayAgain().forEach(v -> config.canPlayAgainMap.put(v, true));
        return config;
    }

    public GameConfigEntity toEntity() {
        GameConfigEntity e = new GameConfigEntity();
        e.setBurnCount(this.burnCount);
        e.setFaceDownCount(this.faceDownCount);
        e.setFaceUpCount(this.faceUpCount);
        e.setHandCount(this.handCount);
        Map<String, String> rules = new HashMap<>();
        this.cardRuleMap.forEach((k, v) -> rules.put(String.valueOf(k), v.name()));
        e.setCardRules(rules);
        e.setAlwaysPlayable(this.alwaysPlayableMap.entrySet().stream()
                .filter(Map.Entry::getValue).map(Map.Entry::getKey).collect(Collectors.toList()));
        e.setCanPlayAgain(this.canPlayAgainMap.entrySet().stream()
                .filter(Map.Entry::getValue).map(Map.Entry::getKey).collect(Collectors.toList()));
        return e;
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
