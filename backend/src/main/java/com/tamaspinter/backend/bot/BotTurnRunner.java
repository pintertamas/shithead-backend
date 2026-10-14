package com.tamaspinter.backend.bot;

import com.tamaspinter.backend.game.CardSelection;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.game.PlayResult;
import com.tamaspinter.backend.model.Player;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

/**
 * Plays the bot seats of a game in memory, inside the invocation that changed the state. A caller that passes a
 * {@link BotMoveListener} also gets the state after every bot move, so the moves can be shown one at a time. Every
 * move goes through {@link GameSession}, the same code that checks human moves.
 */
@Slf4j
public final class BotTurnRunner {
    /**
     * Most bot moves in one invocation. A human seat is reached after at most one round of bot moves, so only a
     * table where bots alone are left can get near it; such a game is then ended by
     * {@link GameSession#finishStalledBotGame()}.
     */
    public static final int MAX_BOT_MOVES = 2000;

    /** Pause before a paced bot move, long enough for the players to follow each play. */
    public static final int BOT_MOVE_DELAY_MS = 1200;

    /**
     * Bot moves that get a pause before them. The pauses come before the second to the (PACED_BOT_MOVES + 1)th move
     * of an invocation, so one request waits for at most PACED_BOT_MOVES pauses (about 5 seconds). Later moves run
     * straight after each other.
     */
    public static final int PACED_BOT_MOVES = 4;

    private static final Sleeper THREAD_SLEEP = BotTurnRunner::sleepOnThread;
    private static final Sleeper NO_PAUSE = millis -> { };
    private static final BotMoveListener NO_LISTENER = session -> { };

    private BotTurnRunner() {
    }

    /** Waits between two bot moves. The real one blocks the thread; tests pass a fake that records the waits. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis);
    }

    /** Told after every bot move, with the session as it is after that move. */
    @FunctionalInterface
    public interface BotMoveListener {
        void onBotMove(GameSession session);
    }

    /** Lets every bot that is not ready yet make its setup swap and mark itself ready. */
    public static void completeSetup(GameSession session) {
        completeSetup(session, BotStrategies::forType);
    }

    /**
     * Test seam: {@link #completeSetup(GameSession)} with the strategy of each bot type supplied by the caller.
     */
    static void completeSetup(GameSession session, Function<BotType, BotStrategy> strategies) {
        if (!session.isStarted() || session.isSetupComplete()) {
            return;
        }
        for (Player player : new ArrayList<>(session.getPlayers())) {
            if (!player.isBot() || player.isReady()) {
                continue;
            }
            SetupSwap swap = chooseSetupSwap(strategies, player, session);
            if (!swap.isEmpty() && !session.swapStartingCards(player.getPlayerId(), swap.handIndices(), swap.faceUpIndices())) {
                log.warn("Bot setup swap was rejected in game {}", session.getSessionId());
            }
            session.markReady(player.getPlayerId());
        }
    }

    /** A failing strategy must never block the game, so any runtime error falls back to keeping the deal. */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static SetupSwap chooseSetupSwap(Function<BotType, BotStrategy> strategies, Player player,
                                             GameSession session) {
        try {
            return strategies.apply(player.getBotType()).chooseSetupSwap(
                    List.copyOf(player.getHand()), List.copyOf(player.getFaceUp()), session.getConfig());
        } catch (RuntimeException e) {
            log.error("Bot setup strategy failed in game {}", session.getSessionId(), e);
            return SetupSwap.none();
        }
    }

    /**
     * Plays while it is a bot's turn and the game is running, up to {@link #MAX_BOT_MOVES} moves, without pauses.
     *
     * @return the number of bot moves made
     */
    public static int playBotTurns(GameSession session) {
        return playBotTurns(session, new Random());
    }

    /**
     * As {@link #playBotTurns(GameSession)}, with the strategies' randomness taken from {@code random}.
     */
    public static int playBotTurns(GameSession session, Random random) {
        return playBotTurns(session, random, BotStrategies::forType);
    }

    /**
     * Plays the bot turns of a WebSocket request, paced: a pause of {@link #BOT_MOVE_DELAY_MS} before the paced moves
     * (see {@link #PACED_BOT_MOVES}), and {@code listener} called after every bot move.
     *
     * @return the number of bot moves made
     */
    public static int playBotTurns(GameSession session, BotMoveListener listener) {
        return playBotTurns(session, new Random(), BotStrategies::forType, THREAD_SLEEP, listener);
    }

    /**
     * Test seam: {@link #playBotTurns(GameSession, Random)} with the strategy of each bot type supplied by the caller.
     */
    static int playBotTurns(GameSession session, Random random, Function<BotType, BotStrategy> strategies) {
        return playBotTurns(session, random, strategies, NO_PAUSE, NO_LISTENER);
    }

    /**
     * Test seam: the whole bot loop, with the pauses and the move listener supplied by the caller.
     */
    static int playBotTurns(GameSession session, Random random, Function<BotType, BotStrategy> strategies,
                            Sleeper sleeper, BotMoveListener listener) {
        int moves = 0;
        while (isBotTurn(session) && moves < MAX_BOT_MOVES) {
            if (isPaced(moves)) {
                sleeper.sleep(BOT_MOVE_DELAY_MS);
            }
            if (!playOneMove(session, random, strategies)) {
                log.error("Bot could not move in game {}; stopping the bot loop", session.getSessionId());
                break;
            }
            moves++;
            listener.onBotMove(session);
        }
        if (moves >= MAX_BOT_MOVES && isBotTurn(session)) {
            log.warn("Bot move cap reached in game {}", session.getSessionId());
            session.finishStalledBotGame();
        }
        return moves;
    }

    /**
     * True before a move that follows {@code movesSoFar} moves of the invocation, while the pauses last.
     */
    private static boolean isPaced(int movesSoFar) {
        return movesSoFar > 0 && movesSoFar <= PACED_BOT_MOVES;
    }

    /** Real pause for the production loop; an interrupted wait just ends early and keeps the interrupt flag. */
    private static void sleepOnThread(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isBotTurn(GameSession session) {
        Player current = session.getCurrentPlayer();
        return session.isStarted() && session.isSetupComplete() && !session.isFinished()
                && current != null && current.isBot() && !current.isOut();
    }

    /** Makes the strategy's move, or a guaranteed legal fallback. False only if not even the fallback worked. */
    private static boolean playOneMove(GameSession session, Random random, Function<BotType, BotStrategy> strategies) {
        Player bot = session.getCurrentPlayer();
        List<List<CardSelection>> legalPlays = session.legalPlays();
        List<CardSelection> choice = choosePlay(strategies, session, bot, legalPlays, random);
        PlayResult result;
        if (choice != null && legalPlays.contains(choice)) {
            result = session.playSelections(choice);
        } else if (choice == null && !session.getDiscardPile().isEmpty()) {
            result = session.pickupPile();
        } else {
            result = PlayResult.INVALID;
        }
        if (result == PlayResult.INVALID) {
            log.warn("Bot move rejected in game {} ({}); using the fallback", session.getSessionId(),
                    session.getLastInvalidReason());
            result = fallbackMove(session, legalPlays);
        }
        return result != PlayResult.INVALID;
    }

    /** A failing strategy must never block the game, so any runtime error falls back to the first legal play. */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static List<CardSelection> choosePlay(Function<BotType, BotStrategy> strategies, GameSession session,
                                                  Player bot, List<List<CardSelection>> legalPlays, Random random) {
        try {
            return strategies.apply(bot.getBotType()).choosePlay(BotView.of(session, bot), legalPlays, random);
        } catch (RuntimeException e) {
            // One line without the stack trace: a broken strategy fails on every move, up to MAX_BOT_MOVES times.
            log.warn("Bot strategy failed in game {}: {}", session.getSessionId(), e.toString());
            return legalPlays.isEmpty() ? null : legalPlays.get(0);
        }
    }

    /** The first legal play, or picking up the pile when nothing can be played. */
    private static PlayResult fallbackMove(GameSession session, List<List<CardSelection>> legalPlays) {
        if (!legalPlays.isEmpty()) {
            PlayResult result = session.playSelections(legalPlays.get(0));
            if (result != PlayResult.INVALID) {
                return result;
            }
        }
        return session.pickupPile();
    }
}
