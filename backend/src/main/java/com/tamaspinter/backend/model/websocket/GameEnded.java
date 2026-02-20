package com.tamaspinter.backend.model.websocket;

import lombok.Builder;

import java.util.List;
import java.util.Map;

@Builder
public record GameEnded(List<String> playerIds, Map<String, Double> results) {
}
