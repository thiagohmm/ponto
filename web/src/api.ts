import type { Bank, Company, CompanyList, Config, DayInfo, Entry, EntryList, Folder, Home, KanbanBoard, KanbanCard, KanbanColumn, Note, NoteFile, NoteList, Sum, Tag } from "./types";
import { ApiError } from "./types";

export type EntrySave = { dia: string; tin: string; tout: string; just: string; closeOpen?: boolean; confirmMidnight?: boolean; confirmOverlap?: boolean };
export type ConfigSave = { who?: string; off?: string; daily?: string; vacuum?: boolean };
let companyId = 0;
export function selectApiCompany(id: number) { companyId = id; }
export function companyUrl(path: string) {
  if (!companyId) throw new Error("Selecione uma empresa antes de continuar.");
  return "/api" + path + (path.includes("?") ? "&" : "?") + "companyId=" + companyId;
}

async function parse(r: Response): Promise<unknown> {
  const text = await r.text();
  if (!text) return {};
  try { return JSON.parse(text); }
  catch { return { error: text }; }
}

function goLogin() {
  if (typeof location === "undefined") return;
  if (location.pathname === "/login") return;
  const next = location.pathname + location.search;
  location.assign("/login" + (next && next !== "/" ? "?next=" + encodeURIComponent(next) : ""));
}

function fail(r: Response, body: { error?: string; message?: string; need?: string; requestId?: string }) {
  if (r.status === 401) goLogin();
  throw new ApiError((body.error || body.message || r.statusText) + (r.status >= 500 && body.requestId ? " (" + body.requestId + ")" : ""), r.status, body.need);
}

async function req<T>(path: string, init?: RequestInit, catalog = false): Promise<T> {
  const r = await fetch(catalog ? "/api" + path : companyUrl(path), {
    ...init,
    credentials: "include",
    headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) },
  });
  if (r.status === 204) return undefined as T;
  const body = await parse(r) as { error?: string; message?: string; need?: string; requestId?: string };
  if (!r.ok) fail(r, body);
  return body as T;
}

async function authReq<T>(path: string, init?: RequestInit): Promise<T> {
  const r = await fetch("/api" + path, {
    ...init,
    credentials: "include",
    headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) },
  });
  const body = await parse(r) as { error?: string; message?: string; need?: string; requestId?: string };
  if (!r.ok) {
    // login/me should not bounce to /login on 401 — the gate handles it
    throw new ApiError(body.error || body.message || r.statusText, r.status, body.need);
  }
  return body as T;
}

export const api = {
  me: () => authReq<{ user: string }>("/me"),
  login: (user: string, password: string) =>
    authReq<{ ok: boolean; user: string }>("/login", { method: "POST", body: JSON.stringify({ user, password }) }),
  logout: () => authReq<{ ok: boolean }>("/logout", { method: "POST" }),
  retryStorage: () => req<{ ok: boolean }>("/storage/retry", { method: "POST" }),
  home: () => req<Home>("/home"),
  entries: (q: { q?: string; dia?: string; n?: number; skip?: number }) => {
    const p = new URLSearchParams();
    if (q.q) p.set("q", q.q);
    if (q.dia) p.set("dia", q.dia);
    if (q.n) p.set("n", String(q.n));
    if (q.skip) p.set("skip", String(q.skip));
    const qs = p.toString();
    return req<EntryList>("/entries" + (qs ? "?" + qs : ""));
  },
  /** A date range (several months) instead of one day/month filter. */
  entriesRange: (from: string, to: string, n = 500, q = "") =>
    req<EntryList>("/entries?from=" + encodeURIComponent(from) + "&to=" + encodeURIComponent(to) + "&n=" + n + "&q=" + encodeURIComponent(q)),
  entry: (id: number) => req<Entry>("/entries/" + id),
  create: (body: EntrySave) =>
    req<Entry>("/entries", { method: "POST", body: JSON.stringify(body) }),
  update: (id: number, body: EntrySave) =>
    req<Entry>("/entries/" + id, { method: "PUT", body: JSON.stringify(body) }),
  remove: (id: number) => req<{ ok: boolean }>("/entries/" + id, { method: "DELETE" }),
  removeDay: (dia: string) => req<{ ok: boolean; deleted: number }>("/days/" + encodeURIComponent(dia), { method: "DELETE" }),
  setFalta: (dia: string, on: boolean) =>
    req<{ ok: boolean; dia: string; diaIso: string; falta: boolean; warn: string }>(
      "/days/" + encodeURIComponent(dia) + "/falta", { method: "PUT", body: JSON.stringify({ on }) }),
  setNormal: (dia: string, on: boolean) =>
    req<{ ok: boolean; dia: string; diaIso: string; normal: boolean; warn: string }>(
      "/days/" + encodeURIComponent(dia) + "/normal", { method: "PUT", body: JSON.stringify({ on }) }),
  bank: (ym?: string) => req<Bank>("/bank" + (ym ? "?ym=" + encodeURIComponent(ym) : "")),
  day: (dia: string) => req<DayInfo>("/day?dia=" + encodeURIComponent(dia)),
  holiday: (dia: string) => req<{ dia: string; off: string }>("/holiday?dia=" + encodeURIComponent(dia)),
  sum: (from: string, to: string) =>
    req<Sum>("/sum?from=" + encodeURIComponent(from) + "&to=" + encodeURIComponent(to)),
  config: () => req<Config>("/config"),
  saveConfig: (body: ConfigSave) =>
    req<Config>("/config", { method: "PUT", body: JSON.stringify(body) }),
  companies: () => req<CompanyList>("/companies", undefined, true),
  createCompany: (body: { name: string; off?: string; daily?: string }) =>
    req<Company>("/companies", { method: "POST", body: JSON.stringify(body) }),
  updateCompany: (id: number, body: { name?: string; off?: string; daily?: string }) =>
    req<Company>("/companies/" + id, { method: "PUT", body: JSON.stringify(body) }),
  removeCompany: (id: number) =>
    req<{ ok: boolean }>("/companies/" + id, { method: "DELETE" }),
  activateCompany: (id: number) =>
    req<Company & { activeId: number }>("/companies/active", { method: "PUT", body: JSON.stringify({ id }) }),
  exportUrl: (kind: string, from: string, to: string) =>
    companyUrl("/export/" + kind + "?from=" + encodeURIComponent(from) + "&to=" + encodeURIComponent(to)),
  notes: (q?: string) =>
    req<NoteList>("/notes" + (q ? "?q=" + encodeURIComponent(q) : "")),
  note: (id: number) => req<Note>("/notes/" + id),
  createNote: (body: { title: string; body: string; folderId?: number | null; tagIds?: number[] }) =>
    req<Note>("/notes", { method: "POST", body: JSON.stringify(body) }),
  updateNote: (id: number, body: { title: string; body: string; revision: number }) =>
    req<Note>("/notes/" + id, { method: "PUT", body: JSON.stringify(body) }),
  moveNote: (id: number, folderId: number | null) =>
    req<Note>("/notes/" + id + "/folder", { method: "PUT", body: JSON.stringify({ folderId }) }),
  tagNote: (id: number, body: { tagId?: number; tagIds?: number[] }) =>
    req<Note>("/notes/" + id + "/tags", { method: "PUT", body: JSON.stringify(body) }),
  removeNote: (id: number) => req<{ ok: boolean }>("/notes/" + id, { method: "DELETE" }),
  files: (noteId: number) => req<{ files: NoteFile[] }>("/notes/" + noteId + "/files"),
  uploadFile: async (noteId: number, file: File) => {
    const fd = new FormData();
    fd.append("file", file);
    const r = await fetch(companyUrl("/notes/" + noteId + "/files"), { method: "POST", body: fd, credentials: "include" });
    const body = await parse(r) as { error?: string; message?: string; need?: string; requestId?: string };
    if (!r.ok) fail(r, body);
    return body as NoteFile;
  },
  removeFile: (noteId: number, fileId: number) =>
    req<{ ok: boolean }>("/notes/" + noteId + "/files/" + fileId, { method: "DELETE" }),
  fileDownloadUrl: (noteId: number, fileId: number) =>
    companyUrl("/notes/" + noteId + "/files/" + fileId + "/download"),
  fileBytes: async (noteId: number, fileId: number): Promise<Blob> => {
    const r = await fetch(companyUrl("/notes/" + noteId + "/files/" + fileId + "/download"), { credentials: "include" });
    if (!r.ok) {
      const body = await parse(r) as { error?: string; message?: string; requestId?: string };
      fail(r, body);
    }
    return r.blob();
  },
  createFolder: (body: { name: string; parent?: number | null }) =>
    req<Folder>("/folders", { method: "POST", body: JSON.stringify(body) }),
  updateFolder: (id: number, body: { name?: string; parent?: number | null }) =>
    req<Folder>("/folders/" + id, { method: "PUT", body: JSON.stringify(body) }),
  removeFolder: (id: number) => req<{ ok: boolean }>("/folders/" + id, { method: "DELETE" }),
  createTag: (body: { name: string; color?: string }) =>
    req<Tag>("/tags", { method: "POST", body: JSON.stringify(body) }),
  updateTag: (id: number, body: { name?: string; color?: string }) =>
    req<Tag>("/tags/" + id, { method: "PUT", body: JSON.stringify(body) }),
  removeTag: (id: number) => req<{ ok: boolean }>("/tags/" + id, { method: "DELETE" }),
  kanban: () => req<KanbanBoard>("/kanban"),
  createKanbanColumn: (body: { name: string }) =>
    req<KanbanColumn>("/kanban/columns", { method: "POST", body: JSON.stringify(body) }),
  updateKanbanColumn: (id: number, body: { name?: string; position?: number }) =>
    req<KanbanColumn>("/kanban/columns/" + id, { method: "PUT", body: JSON.stringify(body) }),
  removeKanbanColumn: (id: number) =>
    req<{ ok: boolean }>("/kanban/columns/" + id, { method: "DELETE" }),
  createKanbanCard: (body: { title: string; body?: string; columnId: number }) =>
    req<KanbanCard>("/kanban/cards", { method: "POST", body: JSON.stringify(body) }),
  updateKanbanCard: (id: number, body: { title: string; body: string; revision: number }) =>
    req<KanbanCard>("/kanban/cards/" + id, { method: "PUT", body: JSON.stringify(body) }),
  moveKanbanCard: (id: number, body: { columnId: number; position: number }) =>
    req<KanbanCard>("/kanban/cards/" + id + "/move", { method: "PUT", body: JSON.stringify(body) }),
  blockKanbanCard: (id: number, body: { kind: string; reason: string }) =>
    req<KanbanCard>("/kanban/cards/" + id + "/block", { method: "POST", body: JSON.stringify(body) }),
  unblockKanbanCard: (id: number) =>
    req<KanbanCard>("/kanban/cards/" + id + "/unblock", { method: "POST", body: "{}" }),
  removeKanbanCard: (id: number) =>
    req<{ ok: boolean }>("/kanban/cards/" + id, { method: "DELETE" }),
};
