// After a deploy, chunk files from the previous build may be gone. A failed chunk load reloads the page once.
// sessionStorage holds the guard so a persistent failure (offline, broken build) cannot loop.
const RELOAD_FLAG = "shithead_chunk_reload";
const CHUNK_ERROR = /Failed to fetch dynamically imported module|error loading dynamically imported module|Importing a module script failed|Unable to preload CSS|ChunkLoadError/i;

let reloading = false;
let handlerInstalled = false;

function readFlag(): boolean {
  try { return window.sessionStorage.getItem(RELOAD_FLAG) === "1"; } catch { return false; }
}

function writeFlag(value: boolean): void {
  try {
    if (value) window.sessionStorage.setItem(RELOAD_FLAG, "1");
    else window.sessionStorage.removeItem(RELOAD_FLAG);
  } catch { /* storage unavailable: the reload guard is simply not persisted */ }
}

/** Reloads the page unless a reload already happened without a successful chunk load since. */
function reloadOnce(): boolean {
  if (reloading) return true;
  if (readFlag()) return false;
  writeFlag(true);
  reloading = true;
  window.location.reload();
  return true;
}

export function isChunkLoadError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : typeof error === "string" ? error : "";
  return CHUNK_ERROR.test(message);
}

/** Wraps a dynamic import. A stale-chunk failure reloads the page once; a successful load clears the guard. */
export function loadRoute<T>(loader: () => Promise<T>): Promise<T> {
  return loader().then(
    (module) => {
      writeFlag(false);
      return module;
    },
    (error: unknown) => {
      if (isChunkLoadError(error) && reloadOnce()) {
        // The page is going away; never settle so the error boundary does not flash.
        return new Promise<T>(() => {});
      }
      throw error;
    },
  );
}

/** Vite fires vite:preloadError when a dynamic import's preload fails (the usual symptom of a redeploy). */
export function installChunkReloadHandler(): void {
  if (handlerInstalled || typeof window === "undefined") return;
  handlerInstalled = true;
  window.addEventListener("vite:preloadError", () => {
    reloadOnce();
  });
}
