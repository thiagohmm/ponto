/**
 * Copying a secret to the clipboard — and the honest part of that sentence.
 *
 * A password put on the clipboard cannot be taken back. We can overwrite it later, and
 * on some platforms that is enough; on others (iOS, and any OS with clipboard history
 * enabled) the value stays wherever the system decided to keep it: cloud sync, KDE
 * Klipper, Ditto, a phone's clipboard. No web app can reach those.
 *
 * So this module does three things and claims only those three:
 *
 * 1. writes the secret, using the asynchronous Clipboard API when it is available and a
 *    `execCommand("copy")` selection fallback only over HTTPS (that path cannot run in a
 *    context the browser does not consider secure);
 * 2. overwrites the clipboard with a neutral string after `ttlMs`, which is the "clear"
 *    part of the promise, not a claim of erasure;
 * 3. returns a value the UI uses to stop showing the secret, because the visible field is
 *    the part the page *can* retract.
 *
 * The message that goes back to the user says what happened, not what we hope happened.
 */

export const COPY_TTL_MS = 15_000;
/** What replaces the secret: neutral text, so a paste looks empty rather than random. */
export const SCRUB_TEXT = "·";

export type Copied = { ok: boolean; scrubbedAt: number; how: "clipboard" | "fallback" | "none" };

/** False everywhere the browser refuses; true on HTTPS and on localhost. */
export function canClipboard(): boolean {
  return typeof navigator !== "undefined" && !!navigator.clipboard;
}

/** Overwrite the clipboard. Failure is silent on purpose: there is nothing to do about it. */
export async function scrub(): Promise<void> {
  try {
    if (canClipboard()) await navigator.clipboard.writeText(SCRUB_TEXT);
  } catch {
    /* the clipboard was not ours to clean; the field still closes */
  }
}

/**
 * Copy a secret, then overwrite it after `ttlMs`.
 *
 * Returns immediately with the deadline, so the countdown in the UI is honest even if the
 * scrub later fails. `how` tells the truth about the fallback: a selection-based copy
 * works without the Clipboard API, but it cannot be scheduled to be overwritten by the
 * browser in the same way — so the field still closes, and the clipboard may keep it.
 */
export async function copySecret(text: string, ttlMs: number = COPY_TTL_MS): Promise<Copied> {
  const scrubbedAt = Date.now() + Math.max(1000, ttlMs);
  if (!text) return { ok: false, scrubbedAt, how: "none" };
  if (canClipboard()) {
    try {
      await navigator.clipboard.writeText(text);
      scheduleScrub(ttlMs);
      return { ok: true, scrubbedAt, how: "clipboard" };
    } catch {
      /* permission denied, or the document lost focus: fall through */
    }
  }
  return { ok: await legacyCopy(text), scrubbedAt, how: "fallback" };
}

function scheduleScrub(ttlMs: number): void {
  setTimeout(() => void scrub(), Math.max(1000, ttlMs));
}

/** The last resort: a hidden textarea and `document.execCommand("copy")`. */
async function legacyCopy(text: string): Promise<boolean> {
  if (typeof document === "undefined") return false;
  const area = document.createElement("textarea");
  area.value = text;
  area.setAttribute("aria-hidden", "true");
  area.style.position = "fixed";
  area.style.top = "-1000px";
  document.body.appendChild(area);
  try {
    area.select();
    const ok = document.execCommand("copy");
    return ok === true;
  } catch {
    return false;
  } finally {
    area.remove();
  }
}

/** The line the UI shows after a copy. One sentence, no promises it cannot keep. */
export function copiedMessage(what: Copied["how"]): string {
  switch (what) {
    case "clipboard":
      return "copied — it will be replaced from the clipboard in a few seconds";
    case "fallback":
      return "copied — this browser may keep the value, clean it yourself";
    case "none":
      return "nothing to copy";
  }
}
