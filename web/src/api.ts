import type { Config, DayInfo, Entry, Home, Sum } from "./types";
import { ApiError } from "./types";

async function parse(r: Response): Promise<unknown> {
  const text = await r.text();
  if (!text) return {};
  try { return JSON.parse(text); }
  catch { return { error: text }; }
}

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  const r = await fetch("/api" + path, {
    ...init,
    headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) },
  });
  if (r.status === 204) return undefined as T;
  const body = await parse(r) as { error?: string; message?: string; need?: string };
  if (!r.ok) {
    throw new ApiError(body.error || body.message || r.statusText, r.status, body.need);
  }
  return body as T;
}

export const api = {
  home: () => req<Home>("/home"),
  entries: (q: { q?: string; dia?: string; n?: number; skip?: number }) => {
    const p = new URLSearchParams();
    if (q.q) p.set("q", q.q);
    if (q.dia) p.set("dia", q.dia);
    if (q.n) p.set("n", String(q.n));
    if (q.skip) p.set("skip", String(q.skip));
    const qs = p.toString();
    return req<{ total: number; shown: number; skip: number; entries: Entry[] }>(
      "/entries" + (qs ? "?" + qs : ""),
    );
  },
  entry: (id: number) => req<Entry>("/entries/" + id),
  create: (body: Record<string, unknown>) =>
    req<Entry>("/entries", { method: "POST", body: JSON.stringify(body) }),
  update: (id: number, body: Record<string, unknown>) =>
    req<Entry>("/entries/" + id, { method: "PUT", body: JSON.stringify(body) }),
  remove: (id: number) => req<{ ok: boolean }>("/entries/" + id, { method: "DELETE" }),
  removeDay: (dia: string) => req<{ ok: boolean; deleted: number }>("/days/" + encodeURIComponent(dia), { method: "DELETE" }),
  day: (dia: string) => req<DayInfo>("/day?dia=" + encodeURIComponent(dia)),
  holiday: (dia: string) => req<{ dia: string; off: string }>("/holiday?dia=" + encodeURIComponent(dia)),
  sum: (from: string, to: string) =>
    req<Sum>("/sum?from=" + encodeURIComponent(from) + "&to=" + encodeURIComponent(to)),
  config: () => req<Config>("/config"),
  saveConfig: (body: Record<string, unknown>) =>
    req<Config>("/config", { method: "PUT", body: JSON.stringify(body) }),
  exportUrl: (kind: string, from: string, to: string) =>
    "/api/export/" + kind + "?from=" + encodeURIComponent(from) + "&to=" + encodeURIComponent(to),
};
