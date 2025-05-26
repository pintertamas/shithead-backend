package com.tamaspinter.backend.game;

import com.tamaspinter.backend.model.CardRule;
import lombok.Getter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class GameConfig {
    @Getter
    private int faceDownCount;
    @Getter
    private int faceUpCount;
    @Getter
    private int handCount;
    @Getter
    private int burnCount;
    private final Map<Integer, CardRule> cardRuleMap = new HashMap<>(); // Maps a card rule to a number (the number must be 2-14)
    private final Map<Integer, Boolean> isAlwaysPlayable = new HashMap<>();
    private final Map<Integer, Boolean> canPlayAgain = new HashMap<>();

    public static GameConfig defaultGameConfig() {
        GameConfig gameConfig = new GameConfig();
        gameConfig.faceDownCount = 3;
        gameConfig.faceUpCount = 3;
        gameConfig.handCount = 3;
        gameConfig.burnCount = 4;
        gameConfig.cardRuleMap.put(2, CardRule.JOKER);
        gameConfig.cardRuleMap.put(6, CardRule.SMALLER);
        gameConfig.cardRuleMap.put(8, CardRule.TRANSPARENT);
        gameConfig.cardRuleMap.put(9, CardRule.REVERSE);
        gameConfig.cardRuleMap.put(10, CardRule.BURNER);
        gameConfig.isAlwaysPlayable.put(2, true);
        gameConfig.isAlwaysPlayable.put(8, true);
        gameConfig.canPlayAgain.put(10, true);

        return gameConfig;
    }

    public CardRule getCardRule(int value) {
        return cardRuleMap.getOrDefault(value, CardRule.DEFAULT);
    }

    public boolean isAlwaysPlayable(int value) {
        return isAlwaysPlayable.getOrDefault(value, false);
    }

    public boolean canPlayAgain(int value) {
        return canPlayAgain.getOrDefault(value, false);
    }

    @Bean
    public GameManager gameManager() {
        return new GameManager();
    }

    // TODO : Function to fetch config from somewhere
}
