package com.tamaspinter.backend.bot;

/** Maps a bot type to its strategy. Strategies are stateless, so one instance per type is shared. */
public final class BotStrategies {
    private static final BotStrategy BEGINNER = new BeginnerBotStrategy();
    private static final BotStrategy INTERMEDIATE = new IntermediateBotStrategy();

    private BotStrategies() {
    }

    public static BotStrategy forType(BotType type) {
        return switch (type) {
            case BEGINNER -> BEGINNER;
            case INTERMEDIATE -> INTERMEDIATE;
        };
    }
}
