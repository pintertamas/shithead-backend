package com.tamaspinter.backend.model.websocket;

import com.tamaspinter.backend.model.Card;
import lombok.Builder;

import java.util.List;

@Builder
public record PlayMessage(String sessionId, List<Card> cards) {
}
