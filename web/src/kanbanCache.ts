/**
 * "Kafeine": the day's kanban card titles that feed the "Gerar justificativa"
 * button on the punch form. One localStorage entry per company+day, each
 * expiring after 5 days — the same retention the server keeps on
 * `kanban_activity`. Titles are not secrets; they are the same text the board
 * already shows. `localStorage` can throw (private mode, quota); every access
 * is guarded and a missing cache simply means "ask the API".
 */

const PREFIX = "ponto-kanban-just:";
export const KANBAN_CACHE_TTL_DAYS = 5;
const TTL_MS = KANBAN_CACHE_TTL_DAYS * 24 * 60 * 60 * 1000;

type CachedDay = { titles: string[]; at: number };

function key(companyId: number, dia: string) {
  return PREFIX + companyId + ":" + dia;
}

/** Titles cached for the company+day, or null when absent, expired or broken. */
export function readKanbanTitles(companyId: number, dia: string, now = Date.now()): string[] | null {
  if (!companyId || !dia) return null;
  const raw = safeStorage(() => localStorage.getItem(key(companyId, dia)));
  if (!raw) return null;
  const parsed = safeJson(raw) as Partial<CachedDay> | null;
  if (!parsed || typeof parsed.at !== "number" || !Array.isArray(parsed.titles)) return null;
  if (now - parsed.at > TTL_MS) {
    safeStorage(() => localStorage.removeItem(key(companyId, dia)));
    return null;
  }
  return parsed.titles.filter((t): t is string => typeof t === "string");
}

/** Stores the day's titles and sweeps every expired kafeine entry. */
export function writeKanbanTitles(companyId: number, dia: string, titles: string[], now = Date.now()): void {
  if (!companyId || !dia) return;
  const entry: CachedDay = { titles, at: now };
  safeStorage(() => localStorage.setItem(key(companyId, dia), JSON.stringify(entry)));
  pruneExpired(now);
}

/** Drops entries older than the TTL (any company, any day). */
export function pruneExpired(now = Date.now()): void {
  safeStorage(() => {
    const doomed: string[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      if (!k || !k.startsWith(PREFIX)) continue;
      const parsed = safeJson(localStorage.getItem(k) ?? "") as Partial<CachedDay> | null;
      if (!parsed || typeof parsed.at !== "number" || now - parsed.at > TTL_MS) doomed.push(k);
    }
    for (const k of doomed) localStorage.removeItem(k);
  });
}

/** `localStorage` can throw (private mode, quota, blocked cookies); a miss is fine. */
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