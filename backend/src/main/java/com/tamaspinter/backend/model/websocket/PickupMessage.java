package com.tamaspinter.backend.model.websocket;

import lombok.Builder;

@Builder
public record PickupMessage(String sessionId) {
}
