package com.tamaspinter.backend.model.api;

import lombok.Builder;

@Builder
public record LeaderboardEntry(String userId, String username, double eloScore) {
}
