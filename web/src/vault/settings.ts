/**
 * The cofre's preferences — and the one thing they are not.
 *
 * These are UI preferences, not secrets: how long the vault stays open when the machine
 * goes quiet. They live in `localStorage` because a person should not have to re-pick the
 * setting every time they open the tab, and because the value is harmless: knowing that this
 * user locks after ten minutes tells an attacker nothing they can use.
 *
 * What must never land here is the same list people get wrong: the master password, the
 * derived key, the salt of a *new* vault before it is sealed, and any decrypted record. This
 * module has no way to store any of them — `put` refuses non-numbers, and the values it
 * reads back are clamped, so a hand-edited `{"idleMinutes": 0}` cannot mean "never lock".
 */

const KEY = "ponto-cofre-settings";
export const DEFAULT_IDLE_MINUTES = 5;
export const MIN_IDLE_MINUTES = 1;
export const MAX_IDLE_MINUTES = 60;

export type VaultSettings = { idleMinutes: number };

export function clampIdleMinutes(value: number): number {
  if (!Number.isFinite(value)) return DEFAULT_IDLE_MINUTES;
  return Math.min(MAX_IDLE_MINUTES, Math.max(MIN_IDLE_MINUTES, Math.round(value)));
}

/** Never "never lock": a missing or broken value falls back to the default. */
/** What the setting says in the UI, so 300 never appears as a number. */
export function idleLabel(minutes: number): string {
  const mins = clampIdleMinutes(minutes);
  return mins === 1 ? "1 min" : `${mins} min`;
}

export function readSettings(): VaultSettings {
  const raw = safeStorage(() => localStorage.getItem(KEY));
  if (!raw) return { idleMinutes: DEFAULT_IDLE_MINUTES };
  const parsed = safeJson(raw) as Partial<VaultSettings> | null;
  const minutes = typeof parsed?.idleMinutes === "number" ? parsed.idleMinutes : DEFAULT_IDLE_MINUTES;
  return { idleMinutes: clampIdleMinutes(minutes) };
}

export function writeSettings(next: Partial<VaultSettings>): VaultSettings {
  const current = readSettings();
  const saved: VaultSettings = { idleMinutes: clampIdleMinutes(next.idleMinutes ?? current.idleMinutes) };
  safeStorage(() => localStorage.setItem(KEY, JSON.stringify(saved)));
  return saved;
}

/** `localStorage` can throw (private mode, quota, blocked cookies); the default is fine. */
function safeStorage<T>(fn: () => T): T | null {
  try {
    return fn();
  } catch {
    return null;
  }
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}
