package com.tamaspinter.backend.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

import java.util.Map;

/**
 * Public card memory of a game with card-counting bots (see {@code bot.CardMemory}). Only written for such games,
 * so other items do not carry it.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class BotMemoryEntity {
    /** Player id to the values everyone saw go into that hand, comma separated. */
    private Map<String, String> knownHands;
    /** Burned cards per value 2..14, comma separated. */
    private String burned;
}
