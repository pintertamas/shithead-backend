package com.tamaspinter.backend.model.websocket;

import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.game.CardSelection;
import lombok.Builder;

import java.util.List;

@Builder
public record PlayMessage(String action, String sessionId, List<Card> cards, List<CardSelection> selections,
        String setupAction, Integer handIndex, Integer faceUpIndex) {
}
