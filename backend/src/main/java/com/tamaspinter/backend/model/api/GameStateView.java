package com.tamaspinter.backend.model.api;

import com.tamaspinter.backend.game.GameEvent;
import com.tamaspinter.backend.model.Card;
import lombok.Builder;

import java.util.List;

@Builder
public record GameStateView(
        String sessionId,
        boolean started,
        boolean starting,
        boolean setupComplete,
        boolean finished,
        String currentPlayerId,
        String shitheadId,
        boolean isOwner,
        int deckCount,
        boolean allowMixedHandAndFaceUpWhenDeckEmpty,
        boolean allowFailedFaceUpPlay,
        boolean voiceEnabled,
        Card revealedCard,
        int discardCount,
        List<Card> discardPile,
        List<PlayerStateView> players,
        List<GameEvent> events
) {
}
