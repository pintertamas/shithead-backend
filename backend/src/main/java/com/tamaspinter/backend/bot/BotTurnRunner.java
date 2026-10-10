package com.tamaspinter.backend.bot;

import com.tamaspinter.backend.game.CardSelection;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.game.PlayResult;
import com.tamaspinter.backend.model.Player;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Plays the bot seats of a game in memory, inside the invocation that changed the state, so callers save and
 * broadcast once. Every move goes through {@link GameSession}, the same code that checks human moves.
 */
@Slf4j
public final class BotTurnRunner {
    /**
     * Most bot moves in one invocation. A human seat is reached after at most one round of bot moves, so only a
     * table where bots alone are left can get near it; such a game is then ended by
     * {@link GameSession#finishStalledBotGame()}.
     */
    public static final int MAX_BOT_MOVES = 2000;

    private BotTurnRunner() {
    }

    /** Lets every bot that is not ready yet make its setup swap and mark itself ready. */
    public static void completeSetup(GameSession session) {
        if (!session.isStarted() || session.isSetupComplete()) {
            return;
        }
        for (Player player : new ArrayList<>(session.getPlayers())) {
            if (!player.isBot() || player.isReady()) {
                continue;
            }
            SetupSwap swap = chooseSetupSwap(player, session);
            if (!swap.isEmpty() && !session.swapStartingCards(player.getPlayerId(), swap.handIndices(), swap.faceUpIndices())) {
                log.warn("Bot setup swap was rejected in game {}", session.getSessionId());
            }
            session.markReady(player.getPlayerId());
        }
    }

    /** A failing strategy must never block the game, so any runtime error falls back to keeping the deal. */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static SetupSwap chooseSetupSwap(Player player, GameSession session) {
        try {
            return BotStrategies.forType(player.getBotType()).chooseSetupSwap(
                    List.copyOf(player.getHand()), List.copyOf(player.getFaceUp()), session.getConfig());
        } catch (RuntimeException e) {
            log.error("Bot setup strategy failed in game {}", session.getSessionId(), e);
            return SetupSwap.none();
        }
    }

    /**
     * Plays while it is a bot's turn and the game is running, up to {@link #MAX_BOT_MOVES} moves.
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
        int moves = 0;
        while (isBotTurn(session) && moves < MAX_BOT_MOVES) {
            if (!playOneMove(session, random)) {
                log.error("Bot could not move in game {}; stopping the bot loop", session.getSessionId());
                break;
            }
            moves++;
        }
        if (moves >= MAX_BOT_MOVES && isBotTurn(session)) {
            log.warn("Bot move cap reached in game {}", session.getSessionId());
            session.finishStalledBotGame();
        }
        return moves;
    }

    private static boolean isBotTurn(GameSession session) {
        Player current = session.getCurrentPlayer();
        return session.isStarted() && session.isSetupComplete() && !session.isFinished()
                && current != null && current.isBot() && !current.isOut();
    }

    /** Makes the strategy's move, or a guaranteed legal fallback. False only if not even the fallback worked. */
    private static boolean playOneMove(GameSession session, Random random) {
        Player bot = session.getCurrentPlayer();
        List<List<CardSelection>> legalPlays = session.legalPlays();
        List<CardSelection> choice = choosePlay(session, bot, legalPlays, random);
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
    private static List<CardSelection> choosePlay(
            GameSession session, Player bot, List<List<CardSelection>> legalPlays, Random random) {
        try {
            return BotStrategies.forType(bot.getBotType()).choosePlay(BotView.of(session, bot), legalPlays, random);
        } catch (RuntimeException e) {
            log.error("Bot strategy failed in game {}", session.getSessionId(), e);
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
