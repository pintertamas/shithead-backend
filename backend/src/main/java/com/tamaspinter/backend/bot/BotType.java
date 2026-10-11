package com.tamaspinter.backend.bot;

import java.util.Locale;

/** Kinds of computer-controlled players the lobby owner can seat. */
public enum BotType {
    BEGINNER("Beginner Bot");

    private final String displayName;

    BotType(String displayName) {
        this.displayName = displayName;
    }

    /** Name prefix of seats of this type, numbered per game ("Beginner Bot 1", "Beginner Bot 2"). */
    public String getDisplayName() {
        return displayName;
    }

    /** Parses a stored or requested type name, case-insensitively. Null or unknown names give null. */
    public static BotType fromName(String name) {
        if (name == null) {
            return null;
        }
        for (BotType type : values()) {
            if (type.name().equals(name.trim().toUpperCase(Locale.ROOT))) {
                return type;
            }
        }
        return null;
    }
}
