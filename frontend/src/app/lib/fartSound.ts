/** Sound played for nudges and once after a successful login. The file lives in public/sounds. */
const FART_SRC = `${import.meta.env.BASE_URL}sounds/fart.mp3`;
/** sessionStorage flag set by the auth callback and consumed by the lobby. */
export const LOGIN_SOUND_KEY = "shithead_login_sound";

/** Most fart sounds that may play at the same time. The oldest one is cut off when a new nudge needs a slot. */
export const MAX_FART_VOICES = 8;
/** Most nudges kept while audio is locked. Nudges beyond this count are dropped so memory stays bounded. */
export const MAX_QUEUED_FARTS = 50;
/** Gap between the starts of queued nudges, so a burst stays audible instead of cutting the oldest voices. */
export const FLUSH_SPACING_MS = 250;

/** A sound that is still playing. `stop` cuts it off. */
type Voice = { stop: () => void };

/** Sounds still playing. Each one removes itself when it ends, so nothing accumulates. */
const voices: Voice[] = [];
/** Nudges waiting for unlocked audio, one count per nudge. They play on the next gesture. */
let queued = 0;
/** Timer of a spaced flush that is still running. While it runs, new entries wait for it to play them. */
let flushTimer: number | null = null;

/** The one AudioContext. It is created inside a user gesture, because iOS only starts audio from a gesture. */
let context: AudioContext | null = null;
/** The decoded fart for `context`. Every nudge starts its own buffer source from it. */
let decoded: AudioBuffer | null = null;
/** The context a download is running for, or null. A download that settles or times out clears it. */
let decoding: AudioContext | null = null;
/** Set when Web Audio cannot be used: no constructor, a refused construction, or a file that fails while running. */
let webAudioFailed = false;

/** Longest a download or decode may take. A phone that locks mid-request can leave it pending forever. */
const FETCH_TIMEOUT_MS = 8000;

/** A download or decode ran past FETCH_TIMEOUT_MS. That says nothing about the file, so it is never final. */
class FartTimeout extends Error {}

const stateListeners = new Set<() => void>();

function notify(): void {
  stateListeners.forEach((listener) => listener());
}

/** Calls the listener when the queue or the audio state changes. Used with useSyncExternalStore. */
export function subscribeFartState(listener: () => void): () => void {
  stateListeners.add(listener);
  return () => {
    stateListeners.delete(listener);
  };
}

function audioContextConstructor(): typeof AudioContext | undefined {
  if (typeof window === "undefined") return undefined;
  return window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
}

/** True when Web Audio is missing or broken, so sounds go through HTMLAudioElement instead. */
function usesElements(): boolean {
  return webAudioFailed || audioContextConstructor() === undefined;
}

function trackVoice(voice: Voice): void {
  if (voices.length >= MAX_FART_VOICES) voices.shift()?.stop();
  voices.push(voice);
}

function untrackVoice(voice: Voice): void {
  const index = voices.indexOf(voice);
  if (index !== -1) voices.splice(index, 1);
}

/** Starts one copy of the fart. Each call has its own source, so two nudges overlap instead of cutting off. */
function playBuffer(audio: AudioContext, buffer: AudioBuffer): void {
  const source = audio.createBufferSource();
  source.buffer = buffer;
  source.connect(audio.destination);
  const voice: Voice = { stop: () => source.stop() };
  source.onended = () => untrackVoice(voice);
  trackVoice(voice);
  source.start(0);
}

/** Plays the fart through an HTMLAudioElement. Resolves false when the browser blocked it (no user gesture yet). */
function playElement(): Promise<boolean> {
  if (typeof Audio === "undefined") return Promise.resolve(false);
  try {
    const audio = new Audio(FART_SRC);
    audio.preload = "auto";
    const voice: Voice = { stop: () => audio.pause() };
    const end = () => untrackVoice(voice);
    audio.addEventListener("ended", end, { once: true });
    audio.addEventListener("error", end, { once: true });
    trackVoice(voice);
    const attempt = audio.play();
    if (!attempt || typeof attempt.then !== "function") return Promise.resolve(true);
    return attempt.then(() => true, () => {
      untrackVoice(voice);
      return false;
    });
  } catch {
    return Promise.resolve(false);
  }
}

function enqueue(): void {
  queued = Math.min(queued + 1, MAX_QUEUED_FARTS);
  notify();
}

/**
 * HTMLAudioElement path: plays every queued nudge now. Called from a gesture, so the browser allows it. A play the
 * browser still blocks goes back into the queue.
 */
function playQueuedElements(): void {
  const count = queued;
  queued = 0;
  notify();
  for (let i = 0; i < count; i += 1) {
    void playElement().then((played) => {
      if (!played) enqueue();
    });
  }
}

/**
 * Plays queued nudges once the context runs and the file is decoded. Nothing plays while either is missing. The first
 * queued sound starts at once; the rest start FLUSH_SPACING_MS apart, so every nudge is heard and a burst does not
 * start all its voices together. A running flush picks up new entries itself.
 */
function drainQueue(): void {
  if (queued === 0 || flushTimer !== null) return;
  if (usesElements()) {
    playQueuedElements();
    return;
  }
  const audio = context;
  if (!audio || !decoded || audio.state !== "running") return;
  playNextQueued(audio, decoded);
}

function playNextQueued(audio: AudioContext, buffer: AudioBuffer): void {
  queued -= 1;
  playBuffer(audio, buffer);
  notify();
  if (queued > 0) {
    flushTimer = window.setTimeout(() => {
      flushTimer = null;
      drainQueue();
    }, FLUSH_SPACING_MS);
  }
}

/**
 * Runs `work` and rejects with FartTimeout when it has not settled within FETCH_TIMEOUT_MS. The abort cancels a
 * download where the browser supports it. The deadline does not depend on the abort, so a request that ignores it
 * still frees the download slot.
 */
function withTimeout<T>(work: (signal: AbortSignal | undefined) => Promise<T>): Promise<T> {
  const controller = typeof AbortController === "undefined" ? undefined : new AbortController();
  return new Promise<T>((resolve, reject) => {
    const timer = window.setTimeout(() => {
      controller?.abort();
      reject(new FartTimeout("fart sound timed out"));
    }, FETCH_TIMEOUT_MS);
    Promise.resolve()
      .then(() => work(controller?.signal))
      .then(
        (value) => {
          window.clearTimeout(timer);
          resolve(value);
        },
        (error: unknown) => {
          window.clearTimeout(timer);
          reject(error);
        },
      );
  });
}

async function downloadFart(signal: AbortSignal | undefined): Promise<ArrayBuffer | null> {
  const response = await fetch(FART_SRC, signal ? { signal } : undefined);
  return response.ok ? response.arrayBuffer() : null;
}

/**
 * Downloads and decodes the fart for one context. The result is kept only while that context is still the current one.
 * A failed or timed-out download is not final: the next gesture, nudge or return to the page tries again. A decode
 * failure is final only when the context is running, because a suspended or interrupted context may be why it failed.
 */
async function loadFart(audio: AudioContext): Promise<void> {
  try {
    const bytes = await withTimeout(downloadFart).catch(() => null);
    if (!bytes) return;
    let buffer: AudioBuffer;
    try {
      buffer = await withTimeout(() => audio.decodeAudioData(bytes));
    } catch (error) {
      if (!(error instanceof FartTimeout) && audio === context && audio.state === "running") webAudioFailed = true;
      return;
    }
    if (audio === context) decoded = buffer;
  } finally {
    if (decoding === audio) decoding = null;
    drainQueue();
  }
}

/** Starts the download and decode for the current context, unless one is already running for it or it is done. */
function ensureDecoded(): void {
  const audio = context;
  if (!audio || decoded || decoding === audio || webAudioFailed) return;
  if (typeof fetch === "undefined") {
    webAudioFailed = true;
    return;
  }
  decoding = audio;
  void loadFart(audio).catch(() => undefined);
}

/** Runs whenever the context may have started running: after resume() and on statechange. */
function handleContextState(): void {
  if (context?.state === "running") {
    ensureDecoded();
    drainQueue();
  }
  notify();
}

/**
 * Closes the current context and forgets its decoded file and sounds. The next gesture creates a new context inside the
 * gesture, because only a gesture can start one on iOS.
 */
function retireContext(): void {
  const old = context;
  context = null;
  decoded = null;
  voices.splice(0);
  if (old) {
    old.onstatechange = null;
    if (old.state !== "closed") {
      try {
        void old.close().catch(() => undefined);
      } catch {
        /* Already closing or closed: nothing else to release. */
      }
    }
  }
  notify();
}

/**
 * Resumes the context. A resume refused inside a gesture means the context cannot run, so it is retired and the next
 * gesture makes a new one. A resume refused outside a gesture (returning to the page) is normal on iOS and changes
 * nothing: the next gesture resumes the same context.
 */
function resumeContext(audio: AudioContext, inGesture: boolean): void {
  audio.resume().then(handleContextState, () => {
    if (inGesture && audio === context && audio.state !== "running") retireContext();
  });
}

/** One silent sample. Older iOS versions only unlock Web Audio when a sound starts inside a gesture. */
function playSilence(audio: AudioContext): void {
  const source = audio.createBufferSource();
  source.buffer = audio.createBuffer(1, 1, 22050);
  source.connect(audio.destination);
  source.start(0);
}

/**
 * Runs inside a user gesture: creates the context on the first call, replaces it when the browser has closed it, and
 * otherwise resumes it when it is not running.
 */
function unlockInGesture(): void {
  const Ctor = audioContextConstructor();
  if (!Ctor) return;
  try {
    if (context?.state === "closed") retireContext();
    if (!context) {
      context = new Ctor();
      context.onstatechange = handleContextState;
    }
    const audio = context;
    if (audio.state === "running") return;
    playSilence(audio);
    resumeContext(audio, true);
  } catch {
    // Web Audio refused: the element path takes over, so nothing waits on a context that will never run.
    webAudioFailed = true;
    context = null;
    drainQueue();
  }
}

/** Any tap or key press on the page. Unlocks audio and plays what is queued. With nothing queued, nothing plays. */
function onGesture(): void {
  unlockInGesture();
  ensureDecoded();
  drainQueue();
}

/**
 * Coming back to the page: a suspended context is resumed (refused on iOS without a gesture, and the next tap resumes
 * it). A context that is already running gets the same recovery as a gesture, minus the silent sample: the download is
 * retried and the queue drains.
 */
function onVisible(): void {
  if (document.visibilityState !== "visible" || !context) return;
  if (context.state === "running") {
    ensureDecoded();
    drainQueue();
    return;
  }
  resumeContext(context, false);
}

/**
 * Plays the fart. Each nudge gets its own sound, so two nudges overlap. While audio is locked (no gesture yet, or the
 * context is not running) the nudge waits in the queue and plays on the next gesture. Resolves true when the sound
 * started at once and false when it was queued. Never rejects.
 */
export function playFart(): Promise<boolean> {
  if (usesElements()) {
    return playElement().then((played) => {
      if (!played) enqueue();
      return played;
    });
  }
  ensureDecoded();
  enqueue();
  drainQueue();
  return Promise.resolve(queued === 0);
}

/** True while a nudge waits for a tap: something is queued and audio is not running yet. */
export function isWaitingForTap(): boolean {
  if (queued === 0) return false;
  if (usesElements()) return true;
  return context?.state !== "running";
}

/** Marks that the next lobby visit should play the login fart. Storage can be blocked, so errors are ignored. */
export function requestLoginSound(): void {
  try {
    sessionStorage.setItem(LOGIN_SOUND_KEY, "1");
  } catch {
    /* Storage blocked: the login sound is simply skipped. */
  }
}

/** Returns true once after requestLoginSound(), clearing the flag. */
export function consumeLoginSound(): boolean {
  try {
    const requested = sessionStorage.getItem(LOGIN_SOUND_KEY) === "1";
    sessionStorage.removeItem(LOGIN_SOUND_KEY);
    return requested;
  } catch {
    return false;
  }
}

/** Returns false when the socket is not open so the caller can show the offline state. */
export function sendNudge(socket: WebSocket | null, sessionId: string): boolean {
  if (!socket || socket.readyState !== WebSocket.OPEN) return false;
  socket.send(JSON.stringify({ action: "nudge", sessionId }));
  return true;
}

/**
 * Installed once, when the module first loads, so the first tap or key press on any screen unlocks audio. The
 * capture phase means it runs before any handler that stops propagation.
 */
function installGestureListeners(): void {
  if (typeof window === "undefined" || typeof document === "undefined") return;
  const options: AddEventListenerOptions = { capture: true, passive: true };
  window.addEventListener("pointerdown", onGesture, options);
  window.addEventListener("touchend", onGesture, options);
  window.addEventListener("keydown", onGesture, options);
  document.addEventListener("visibilitychange", onVisible);
}

installGestureListeners();
