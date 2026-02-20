package com.tamaspinter.backend.game;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GameManager {
    private final Map<String, GameSession> sessions = new ConcurrentHashMap<>();

    public GameSession createSession(String sessionId) {
        GameSession session = GameSession.builder()
                .sessionId(sessionId)
                .build();
        sessions.put(sessionId, session);
        return session;
    }

    public GameSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
    }
}
