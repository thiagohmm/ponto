/**
 * Auto-lock: the vault closes itself when the machine goes quiet.
 *
 * A unlocked vault is an unlocked vault for whoever is nearby — a shared office terminal,
 * a laptop handed to a child, a screen the user left facing the wrong way. The key lives
 * in this page's memory, so anyone with the tab open and no lock can read every record.
 *
 * The rules, and the reasoning behind each:
 *
 * - **Activity resets the clock.** Keyboard and pointer events, because that is what
 *   "the person is still here" looks like. Scrolling counts; the point is presence, not
 *   which control was used.
 * - **A hidden tab keeps counting.** If the user switches windows, the vault should close
 *   on schedule. Waking on `visibilitychange` would keep it open for exactly the case
 *   people forget: the laptop locked, the tab still unlocked behind it.
 * - **The lock is not a suggestion.** `onLock` drops the key reference, clears every
 *   decrypted field and hides the list. The ciphertext stays in memory (it is harmless and
 *   re-fetching would cost a round trip), the plaintext does not.
 * - **Only one timer.** Starting twice means two futures calling `onLock`, and a second
 *   one firing right after the user unlocks again looks like a bug nobody can reproduce.
 */

export const AUTO_LOCK_MS = 5 * 60_000;
export const MIN_AUTO_LOCK_MS = 30_000;
export const MAX_AUTO_LOCK_MS = 60 * 60_000;

export function clampIdle(ms: number): number {
  return Math.min(MAX_AUTO_LOCK_MS, Math.max(MIN_AUTO_LOCK_MS, Math.round(ms)));
}

export type IdleLock = {
  touch: () => void;
  start: () => void;
  stop: () => void;
  /** Milliseconds left before the vault locks; 0 when already locked. */
  remaining: () => number;
  running: () => boolean;
};

/**
 * The timer itself, so it can be tested without a browser: `start` listens, `stop`
 * detaches, `touch` restarts. In jsdom `window` exists; the guards are for the case where
 * this module is imported outside a page (a test of the maths, or a worker).
 */
export function createIdleLock(onLock: () => void, ms: number = AUTO_LOCK_MS, target: Window | undefined = typeof window === "undefined" ? undefined : window): IdleLock {
  const wait = clampIdle(ms);
  let deadline = 0;
  let timer: ReturnType<typeof setTimeout> | null = null;

  const fire = () => {
    timer = null;
    deadline = 0;
    onLock();
  };

  const arm = () => {
    if (timer !== null) clearTimeout(timer);
    deadline = Date.now() + wait;
    timer = setTimeout(fire, wait);
  };

  const activity = () => {
    if (timer !== null) arm();
  };

  const EVENTS = ["keydown", "pointerdown", "pointermove", "wheel", "scroll", "focus"] as const;

  return {
    touch: activity,
    start: () => {
      activity0();
      arm();
      for (const name of EVENTS) target?.addEventListener(name, activity, { passive: true });
    },
    stop: () => {
      if (timer !== null) {
        clearTimeout(timer);
        timer = null;
      }
      deadline = 0;
      for (const name of EVENTS) target?.removeEventListener(name, activity);
    },
    remaining: () => (timer === null ? 0 : Math.max(0, deadline - Date.now())),
    running: () => timer !== null,
  };

  // The first `touch` establishes that the clock only runs after an unlock; without it,
  // an event that arrived between unlock and start could arm the timer twice.
  function activity0(): void {
    deadline = Date.now() + wait;
  }
}
