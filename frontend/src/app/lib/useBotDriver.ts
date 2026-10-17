import { useEffect, useRef } from "react";
import { GameStateView, PlayerState, playBotTurn } from "../api/game";

/** Pause before each bot move: uniformly random in this window, drawn anew for every bot turn. */
export const BOT_TURN_MIN_MS = 2000;
export const BOT_TURN_MAX_MS = 3000;
/**
 * Extra wait for every seated human except the first one still in play. Only that first human's client drives the
 * bot turn at once; the others step in after this longer wait if the turn is still pending.
 */
export const FALLBACK_EXTRA_MS = 6000;

export interface PendingBotTurn {
  botId: string;
  /** Seq of the newest event; the server plays the move only while this is still the newest one. */
  expectedMoves: number;
  /** True for a seated human who is not the first one in play: that client waits FALLBACK_EXTRA_MS longer. */
  fallback: boolean;
}

/** A player with no cards left is out. The server sets its out flag in exactly this case. */
export function isOutPlayer(player: PlayerState): boolean {
  return player.handCount === 0 && player.faceUp.length === 0 && player.faceDownCount === 0;
}

/**
 * True when failed-flip reveals may be shown on this table: the viewer is a human still in play. Once no human is in
 * play (the bots finish the game on the server) and for a spectating human who is out, the table shows no reveal.
 */
export function revealsShown(state: GameStateView): boolean {
  const you = state.players.find((player) => player.isYou);
  return !!you && !you.isBot && !isOutPlayer(you) && state.players.some((player) => !player.isBot && !isOutPlayer(player));
}

function newestEventSeq(state: GameStateView): number {
  const events = state.events ?? [];
  return events.length === 0 ? 0 : events[events.length - 1].seq;
}

/**
 * The bot turn this client should drive now, or null. A bot turn is driven only in a started, set-up, unfinished game
 * whose current player is a bot while at least one human is still in play, and only by a seated human.
 */
export function pendingBotTurn(state: GameStateView | null): PendingBotTurn | null {
  if (!state || !state.started || state.finished || !state.setupComplete) return null;
  const current = state.players.find((player) => player.playerId === state.currentPlayerId);
  if (!current?.isBot) return null;
  const humansInPlay = state.players.filter((player) => !player.isBot && !isOutPlayer(player));
  const you = state.players.find((player) => player.isYou);
  if (humansInPlay.length === 0 || !you || you.isBot) return null;
  return {
    botId: current.playerId,
    expectedMoves: newestEventSeq(state),
    fallback: humansInPlay[0].playerId !== you.playerId
  };
}

export interface BotTurnDriverDeps {
  play: (expectedPlayerId: string, expectedMoves: number) => Promise<GameStateView>;
  onState: (state: GameStateView) => void;
  random: () => number;
  setTimer: (callback: () => void, ms: number) => unknown;
  clearTimer: (handle: unknown) => void;
}

/**
 * Drives one pending bot turn: waits, makes the call once, and retries once after the same wait if the call fails.
 * At most one timer is pending at any time. Returns the cancel function, which the caller runs when the state moves on
 * (the next state update changes the turn) or when it unmounts.
 */
export function driveBotTurn(turn: PendingBotTurn, deps: BotTurnDriverDeps): () => void {
  let cancelled = false;
  let retried = false;
  let handle: unknown = null;
  const wait = BOT_TURN_MIN_MS + deps.random() * (BOT_TURN_MAX_MS - BOT_TURN_MIN_MS) + (turn.fallback ? FALLBACK_EXTRA_MS : 0);
  const fire = () => {
    handle = null;
    deps.play(turn.botId, turn.expectedMoves).then(
      (next) => {
        if (!cancelled) deps.onState(next);
      },
      () => {
        if (cancelled || retried) return;
        retried = true;
        handle = deps.setTimer(fire, wait);
      }
    );
  };
  handle = deps.setTimer(fire, wait);
  return () => {
    cancelled = true;
    if (handle !== null) deps.clearTimer(handle);
    handle = null;
  };
}

/**
 * Lets this client play the bots' moves while a human is still in the game (see pendingBotTurn). Nothing is shown;
 * the table already shows whose turn it is. `onState` receives the state the server returns for a move.
 */
export function useBotDriver(
  sessionId: string | undefined,
  token: string | undefined,
  state: GameStateView | null,
  onState: (next: GameStateView) => void
): void {
  const turn = pendingBotTurn(state);
  const botId = turn?.botId ?? null;
  const expectedMoves = turn?.expectedMoves ?? -1;
  const fallback = turn?.fallback ?? false;
  const onStateRef = useRef(onState);

  useEffect(() => {
    onStateRef.current = onState;
  }, [onState]);

  useEffect(() => {
    if (!sessionId || !token || botId === null) return undefined;
    const id = sessionId;
    const bearer = token;
    return driveBotTurn({ botId, expectedMoves, fallback }, {
      play: (expectedPlayerId, moves) => playBotTurn(bearer, id, expectedPlayerId, moves),
      onState: (next) => onStateRef.current(next),
      random: Math.random,
      setTimer: (callback, ms) => setTimeout(callback, ms),
      clearTimer: (handle) => clearTimeout(handle as ReturnType<typeof setTimeout>)
    });
  }, [sessionId, token, botId, expectedMoves, fallback]);
}
