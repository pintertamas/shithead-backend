package com.tamaspinter.backend.model.api;

import com.tamaspinter.backend.model.Card;
import lombok.Builder;

import java.util.List;

@Builder
public record GameStateView(
        String sessionId,
        boolean started,
        boolean finished,
        String currentPlayerId,
        String shitheadId,
        boolean isOwner,
        int deckCount,
        int discardCount,
        List<Card> discardPile,
        List<PlayerStateView> players
) {
}

