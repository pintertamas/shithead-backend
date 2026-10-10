/*
 * One game WebSocket per table screen that reconnects by itself.
 *
 * - A drop, or an attempt that fails before it opens, schedules a new attempt with backoff: 1 s, 2 s, 4 s, ... capped
 *   at 15 s. Attempts that never open within CONNECT_TIMEOUT_MS count as failed.
 * - After RECONNECT_GIVE_UP_MS of continuous failed attempts, automatic retries stop. The listener keeps its
 *   "unreachable" state until the socket opens.
 * - Resuming the page (visibilitychange to visible, pageshow from the back/forward cache), regaining the network
 *   (online) and send() always start attempts again, even after automatic retries stopped. A phone that was suspended
 *   can keep reporting OPEN for a dead socket, so readyState is not trusted there.
 * - send() never queues. When the socket is not open it starts a fresh connection and returns false; the caller
 *   tells the player to send the action again.
 */

/** Delay before the first retry after a drop. Doubles with each failed attempt. */
export const RECONNECT_BASE_DELAY_MS = 1000;
/** Upper bound for the retry delay. */
export const RECONNECT_MAX_DELAY_MS = 15000;
/** An attempt that has not opened after this long is abandoned and counts as failed. */
export const CONNECT_TIMEOUT_MS = 10000;
/** Failed attempts in a row before the listener is told the server cannot be reached. */
export const UNREACHABLE_AFTER_FAILURES = 3;
/**
 * Automatic retries stop once failed attempts have run this long without an open, measured from the first failed
 * attempt of the streak. Stops the authorizer being hit every 15 s for as long as the table stays open.
 */
export const RECONNECT_GIVE_UP_MS = 10 * 60 * 1000;

export type GameSocketListener = {
  /** Every open. `reconnected` is false only for the first open. */
  onOpen: (reconnected: boolean) => void;
  /** The current socket went away: it closed, dropped, or was replaced by a fresh one. */
  onClose: () => void;
  onMessage: (event: MessageEvent) => void;
  /** Called after each failed attempt once UNREACHABLE_AFTER_FAILURES attempts have failed in a row. */
  onUnreachable: () => void;
};

/** Backoff before the next attempt, given the failed attempts since the last open. */
export function retryDelayMs(failedAttempts: number): number {
  return Math.min(RECONNECT_BASE_DELAY_MS * 2 ** failedAttempts, RECONNECT_MAX_DELAY_MS);
}

export class GameSocket {
  private readonly createSocket: () => WebSocket;
  private readonly listener: GameSocketListener;
  private socket: WebSocket | null = null;
  /** The current socket has reached OPEN, so its close is a drop rather than a failed attempt. */
  private established = false;
  private hasOpened = false;
  private failedAttempts = 0;
  /** When the current streak of failed attempts began (the first failure after the last open or manual start). */
  private failureStreakStartedAt: number | null = null;
  private retryTimer: number | null = null;
  private connectTimer: number | null = null;
  private disposed = false;

  constructor(createSocket: () => WebSocket, listener: GameSocketListener) {
    this.createSocket = createSocket;
    this.listener = listener;
  }

  /**
   * Opens the first socket and starts listening for resume and network events. Throws when the socket cannot be
   * created at all (for example a bad WebSocket URL); later failures are retried instead.
   */
  start(): void {
    this.connect();
    document.addEventListener("visibilitychange", this.handleVisibilityChange);
    window.addEventListener("pageshow", this.handlePageShow);
    window.addEventListener("online", this.handleOnline);
  }

  /** The live socket, or null while disconnected. */
  currentSocket(): WebSocket | null {
    return this.socket;
  }

  /**
   * Sends when the socket is open and returns true. Otherwise returns false and starts a fresh connection, unless one
   * is already opening. Nothing is queued.
   */
  send(data: string): boolean {
    const ws = this.socket;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(data);
      return true;
    }
    if (!ws || ws.readyState !== WebSocket.CONNECTING) this.reopen();
    return false;
  }

  /**
   * Closes the current socket, if any, and opens a fresh one now. Used on resume, on network return and on a send while
   * disconnected. A manual start also begins a new failure streak, so it works after automatic retries stopped.
   */
  reopen(): void {
    if (this.disposed) return;
    this.resetFailures();
    this.clearRetryTimer();
    this.closeCurrent();
    this.attempt();
  }

  /** Closes the socket and cancels every timer and listener. The manager cannot be used afterwards. */
  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.clearRetryTimer();
    document.removeEventListener("visibilitychange", this.handleVisibilityChange);
    window.removeEventListener("pageshow", this.handlePageShow);
    window.removeEventListener("online", this.handleOnline);
    this.closeCurrent();
  }

  private readonly handleVisibilityChange = () => {
    if (document.visibilityState === "visible") this.reopen();
  };

  private readonly handlePageShow = (event: PageTransitionEvent) => {
    // A normal load has just opened a fresh socket, so only a page restored from the back/forward cache needs a reopen.
    if (event.persisted) this.reopen();
  };

  private readonly handleOnline = () => {
    this.reopen();
  };

  /** Throws when createSocket throws; the caller decides whether that is fatal. */
  private connect(): void {
    const ws = this.createSocket();
    this.socket = ws;
    this.connectTimer = window.setTimeout(() => {
      this.connectTimer = null;
      if (ws.readyState === WebSocket.CONNECTING) ws.close();
    }, CONNECT_TIMEOUT_MS);
    ws.onopen = () => this.handleOpen(ws);
    ws.onmessage = (event) => {
      if (this.socket === ws) this.listener.onMessage(event);
    };
    // Browsers always follow an error with a close, and handleClose decides what happens next.
    ws.onerror = () => undefined;
    ws.onclose = () => this.handleClose(ws);
  }

  /** A retry that cannot even create the socket counts as a failed attempt. */
  private attempt(): void {
    try {
      this.connect();
    } catch {
      this.recordFailure();
      this.scheduleRetry();
    }
  }

  private handleOpen(ws: WebSocket): void {
    if (this.socket !== ws) return;
    this.clearConnectTimer();
    const reconnected = this.hasOpened;
    this.hasOpened = true;
    this.established = true;
    this.resetFailures();
    this.listener.onOpen(reconnected);
  }

  private handleClose(ws: WebSocket): void {
    if (this.socket !== ws) return;
    this.socket = null;
    this.clearConnectTimer();
    const wasEstablished = this.established;
    this.established = false;
    this.listener.onClose();
    if (!wasEstablished) this.recordFailure();
    this.scheduleRetry();
  }

  private recordFailure(): void {
    if (this.failedAttempts === 0) this.failureStreakStartedAt = Date.now();
    this.failedAttempts += 1;
    if (this.failedAttempts >= UNREACHABLE_AFTER_FAILURES) this.listener.onUnreachable();
  }

  /** Schedules the next automatic attempt, unless the failure streak has run for RECONNECT_GIVE_UP_MS. */
  private scheduleRetry(): void {
    if (this.disposed) return;
    this.clearRetryTimer();
    const streakStart = this.failureStreakStartedAt;
    if (streakStart !== null && Date.now() - streakStart >= RECONNECT_GIVE_UP_MS) return;
    this.retryTimer = window.setTimeout(() => {
      this.retryTimer = null;
      this.attempt();
    }, retryDelayMs(this.failedAttempts));
  }

  private resetFailures(): void {
    this.failedAttempts = 0;
    this.failureStreakStartedAt = null;
  }

  /** Detaches the handlers first, so a socket that is being replaced cannot report back into the new one. */
  private closeCurrent(): void {
    this.clearConnectTimer();
    const ws = this.socket;
    if (!ws) return;
    this.socket = null;
    this.established = false;
    ws.onopen = null;
    ws.onmessage = null;
    ws.onerror = null;
    ws.onclose = null;
    ws.close(1000);
    this.listener.onClose();
  }

  private clearRetryTimer(): void {
    if (this.retryTimer !== null) window.clearTimeout(this.retryTimer);
    this.retryTimer = null;
  }

  private clearConnectTimer(): void {
    if (this.connectTimer !== null) window.clearTimeout(this.connectTimer);
    this.connectTimer = null;
  }
}
