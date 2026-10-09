/** Sound played for nudges and once after a successful login. The file lives in public/sounds. */
const FART_SRC = `${import.meta.env.BASE_URL}sounds/fart.mp3`;
/** sessionStorage flag set by the auth callback and consumed by the lobby. */
export const LOGIN_SOUND_KEY = "shithead_login_sound";

const audio: HTMLAudioElement | null = typeof Audio === "undefined" ? null : new Audio(FART_SRC);
if (audio) audio.preload = "auto";

/**
 * Plays the fart. Resolves true when playback started and false when the browser blocked it (autoplay policy
 * or no user gesture yet). The rejection is swallowed on purpose: a blocked fart is not an error.
 */
export function playFart(): Promise<boolean> {
  if (!audio) return Promise.resolve(false);
  try {
    audio.currentTime = 0;
    const attempt = audio.play();
    if (!attempt || typeof attempt.then !== "function") return Promise.resolve(true);
    return attempt.then(() => true, () => false);
  } catch {
    return Promise.resolve(false);
  }
}

let unlockListening = false;

/**
 * Plays the fart on the first pointer or key press anywhere on the page. Used when the browser blocked an
 * automatic playback, so the sound still happens as soon as the user interacts. Only one listener is kept.
 */
export function unlockAudio(): void {
  if (unlockListening || typeof window === "undefined") return;
  unlockListening = true;
  const onGesture = () => {
    window.removeEventListener("pointerdown", onGesture);
    window.removeEventListener("keydown", onGesture);
    unlockListening = false;
    void playFart();
  };
  window.addEventListener("pointerdown", onGesture);
  window.addEventListener("keydown", onGesture);
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
