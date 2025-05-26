package com.tamaspinter.backend.game;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GameManager {
    private final Map<String, GameSession> sessions = new ConcurrentHashMap<>();

    public GameSession createSession(String sessionId) {
        GameSession s = new GameSession(sessionId);
        sessions.put(sessionId, s);
        return s;
    }

    public GameSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
    }
}
