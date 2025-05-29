package com.tamaspinter.backend.model.websocket;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Setter
@Getter
public class GameEnded {
    private List<String> playerIds;
    private Map<String, Double> results; // mapping playerId -> score (1 or 0)
}