package com.tamaspinter.backend.game;

import com.tamaspinter.backend.model.Card;
import lombok.Builder;

import java.util.List;

/**
 * One structured activity-feed entry. {@code count} is the number of cards involved (played, burned or
 * picked up); {@code cards} lists the played or revealed cards when they matter for the message.
 */
@Builder
public record GameEvent(
        long seq,
        GameEventType type,
        String playerId,
        String username,
        List<Card> cards,
        int count,
        long ts
) {
}
