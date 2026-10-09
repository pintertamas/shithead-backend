package com.tamaspinter.backend.game;

/**
 * Validates session chat text. Chat is relayed only and never persisted or logged, so this is the only
 * server-side rule: trim, drop empty messages silently, reject anything over {@link #MAX_LENGTH} code points.
 */
public final class ChatMessageValidator {
    public static final int MAX_LENGTH = 300;

    private ChatMessageValidator() {
    }

    public enum Status {
        ACCEPTED,
        EMPTY,
        TOO_LONG
    }

    public record Result(Status status, String text) {
    }

    public static Result validate(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return new Result(Status.EMPTY, "");
        }
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_LENGTH) {
            return new Result(Status.TOO_LONG, "");
        }
        return new Result(Status.ACCEPTED, trimmed);
    }
}
