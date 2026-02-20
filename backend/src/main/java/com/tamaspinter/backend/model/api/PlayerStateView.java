package com.tamaspinter.backend.model.api;

import com.tamaspinter.backend.model.Card;
import lombok.Builder;

import java.util.List;

@Builder
public record PlayerStateView(
        String playerId,
        String username,
        int handCount,
        List<Card> faceUp,
        int faceDownCount,
        boolean isYou,
        List<Card> hand
) {
}
