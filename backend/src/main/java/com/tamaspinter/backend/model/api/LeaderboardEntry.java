package com.tamaspinter.backend.model.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

/**
 * One leaderboard row. {@code eloScore} is the player's current rating. {@code eloBefore} and {@code eloAfter}
 * are the rating before and after the game the session leaderboard refers to; both are null when no change was
 * recorded and are then omitted from the JSON, so the global leaderboard response is unchanged. {@code shithead} is
 * true only for the seat that lost a finished game; the session leaderboard sets it on every row, and it is null
 * (omitted from the JSON) on the global leaderboard.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LeaderboardEntry(String userId, String username, double eloScore, Double eloBefore, Double eloAfter,
        Boolean shithead) {
}
