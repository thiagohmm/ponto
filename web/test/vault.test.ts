import { afterEach, beforeAll, beforeEach, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { webcrypto } from "node:crypto";
import { api, selectApiCompany } from "../src/api";
import { deriveKey, open, seal, sealCheck } from "../src/vault/crypto";
import { exportFile, fileName, looksLikePlaintextList, readVaultFile } from "../src/vault/file";
import { DEFAULTS, MAX_LENGTH, alphabetFor, bitsOf, generate, randomInt } from "../src/vault/generator";
import { poolSize, strength } from "../src/vault/strength";
import { duplicateCount, duplicateSummary, duplicates } from "../src/vault/duplicates";
import { createIdleLock } from "../src/vault/session";
import { clampIdleMinutes, idleLabel, readSettings, writeSettings } from "../src/vault/settings";
import { blank, label, parsePayload, tooLong, type VaultOpen } from "../src/vault/model";
import { canClipboard, copiedMessage, copySecret, scrub } from "../src/vault/clipboard";
import { useVaultWorkspace } from "../src/vault/Vault";

/**
 * These tests do not re-check AES — `vaultCrypto.test.ts` does that against a Node mirror and
 * against Java. What is checked here is the part no browser can verify for itself: which bytes
 * leave the tab, what a wrong answer means, and what the page promises when the answer is "no".
 */

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
  localStorage.clear();
});

// jsdom's `crypto.subtle` accepts calls and fails in ways no browser does: the global is
// replaced with Node's real WebCrypto, the same swap `vaultCrypto.test.ts` makes.
beforeAll(() => vi.stubGlobal("crypto", webcrypto));

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

const SALT = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
const MASTER = "café-π 123";
const META = { sealed: true, kdf: "PBKDF2-SHA256", salt: SALT, iterations: 100_000, check: "", items: 0, updatedAt: "" };
const originalRandom = Math.random;

// ---- what leaves the tab ------------------------------------------------

it("binds every vault call to a company and sends credentials with it", async () => {
  const fetcher = vi.fn(async () => json({ items: [] }));
  vi.stubGlobal("fetch", fetcher);
  selectApiCompany(11);
  await api.vaultItems();
  await api.vaultItems(7);
  await api.vault();
  expect(fetcher.mock.calls[0][0]).toBe("/api/vault/items?companyId=11");
  expect(fetcher.mock.calls[1][0]).toBe("/api/vault/items?company=7&companyId=11");
  expect(fetcher.mock.calls[2][0]).toBe("/api/vault?companyId=11");
  expect(fetcher.mock.calls[0][1]).toMatchObject({ credentials: "include" });
});

it("seals before it sends: no request body carries a password", async () => {
  const fetcher = vi.fn().mockResolvedValue(json({ id: 1, companyId: 11, blob: "", revision: 1, createdAt: "", updatedAt: "" }));
  vi.stubGlobal("fetch", fetcher);
  const key = await deriveKey(MASTER, SALT, 100_000);
  const blob = await seal(key, JSON.stringify({ app: "Gmail", user: "ana", pass: "s3cr3t-word", desc: "", company: 11 }), "ponto:vault:item:11");
  await api.createVaultItem({ companyId: 11, blob });
  const sent = String(fetcher.mock.calls[0][1]?.body);
  expect(sent).toContain('"blob"');
  expect(sent).not.toContain("s3cr3t-word");
  expect(sent).not.toContain("café");
});

it("treats a stale revision as a refusal, not a retry", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ error: "revision 4 ≠ 7" }, 409)));
  await expect(api.updateVaultItem(3, { blob: "AAA=", revision: 4 })).rejects.toMatchObject({ status: 409 });
});

it("keeps the server's next step when the vault does not exist yet", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ need: "create", error: "Creae the cofre first" }, 423)));
  await expect(api.createVaultItem({ companyId: 11, blob: "AAA=" })).rejects.toMatchObject({ status: 423, need: "create" });
});

// ---- the backup file ----------------------------------------------------

function openRow(id: number, companyId: number, app: string, user: string, pass: string, desc: string): VaultOpen {
  return { id, companyId, blob: "", revision: 1, createdAt: "", updatedAt: "", plain: { app, user, pass, desc, companyId } };
}

it("round-trips a backup and shows no plaintext in the file", async () => {
  const items = [openRow(1, 11, "Gmail", "ana", "alpha-secret-one", ""), openRow(2, 12, "VPN", "", "beta-secret-two", "seat 4")];
  const text = await (await exportFile(items, MASTER, SALT, 100_000)).text();
  expect(text).not.toContain("alpha-secret-one");
  expect(text).not.toContain("beta-secret-two");
  expect(text).not.toContain("Gmail");
  expect(text).not.toContain('"ana"');
  expect(text).not.toContain("seat 4");

  const back = await readVaultFile(text, MASTER);
  expect(back).toHaveLength(2);
  expect(back[0]).toMatchObject({ app: "Gmail", user: "ana", pass: "alpha-secret-one", companyId: 11 });
  expect(back[1].desc).toBe("seat 4");

  await expect(readVaultFile(text, "not-the-password")).rejects.toThrow(/did not open/);
  expect(fileName()).toMatch(/^ponto-cofre-\d{4}-\d\d-\d\dT\d\d-\d\d-\d\d\.json$/);
});

it("refuses a plaintext list before deriving anything", async () => {
  await expect(readVaultFile('[{"app":"x","pass":"honey"}]', MASTER)).rejects.toThrow(/in the clear/);
  expect(looksLikePlaintextList('[{"pass":"x"}]')).toBe(true);
  expect(looksLikePlaintextList('{"a":1}')).toBe(false);
  expect(looksLikePlaintextList("not json")).toBe(false);
});

it("refuses a header that was edited to match a shorter list", async () => {
  const text = await (await exportFile([openRow(1, 11, "A", "", "aa", "")], "pw-12345678", SALT, 100_000)).text();
  const trimmed = text.replace('"count": 1', '"count": 0');
  expect(trimmed).not.toBe(text);
  await expect(readVaultFile(trimmed, "pw-12345678")).rejects.toThrow();
});

it("names a foreign format instead of blaming the password", async () => {
  await expect(readVaultFile(JSON.stringify({ format: "other/1" }), MASTER)).rejects.toThrow(/format/);
  await expect(readVaultFile(JSON.stringify({ format: "ponto-cofre/1", kdf: "argon2" }), MASTER)).rejects.toThrow(/argon2/);
  await expect(readVaultFile("{ not json", MASTER)).rejects.toThrow(/does not parse/);
});

// ---- the generator ------------------------------------------------------

it("does not use Math.random", () => {
  const spy = vi.fn(() => 0.5);
  // The page asks the platform for bytes, so a seeded PRNG cannot make the vault predictable.
  Object.defineProperty(Math, "random", { value: spy, configurable: true, writable: true });
  const pass = generate({ length: 24 });
  expect(pass).toHaveLength(24);
  expect(spy).not.toHaveBeenCalled();
  Object.defineProperty(Math, "random", { value: originalRandom, configurable: true, writable: true });
});

it("covers every chosen set and refuses the impossible requests", () => {
  for (let i = 0; i < 200; i++) {
    const pass = generate({ length: 8, upper: true, lower: true, digits: true, symbols: true });
    expect(/[A-Z]/.test(pass) && /[a-z]/.test(pass) && /[0-9]/.test(pass) && /[^A-Za-z0-9]/.test(pass)).toBe(true);
  }
  expect(() => generate({ upper: false, lower: false, digits: false, symbols: false })).toThrow(/character set/);
  expect(() => generate({ length: 7 })).toThrow(/longueur/);
  expect(() => generate({ length: MAX_LENGTH + 1 })).toThrow(/longueur/);
});

it("is uniform: randomInt rejects the partial range instead of biasing it", () => {
  const seen = new Map<number, number>();
  for (let i = 0; i < 4000; i++) {
    const n = randomInt(7);
    seen.set(n, (seen.get(n) ?? 0) + 1);
  }
  expect([...seen.keys()].sort()).toEqual([0, 1, 2, 3, 4, 5, 6]);
  const counts = [...seen.values()];
  const mean = 4000 / 7;
  expect(Math.max(...counts) / mean).toBeLessThan(1.35);
  expect(() => randomInt(0)).toThrow(/range/);
});

it("counts the alphabet it will actually use", () => {
  expect(alphabetFor(DEFAULTS)).toHaveLength(4);
  expect(bitsOf({ ...DEFAULTS, upper: false, lower: false, digits: false, symbols: false })).toBe(0);
  expect(alphabetFor({ ...DEFAULTS, avoidSimilar: true }).join("")).not.toMatch(/[il1LoO0]/);
  expect(bitsOf({ ...DEFAULTS, length: 8 })).toBeLessThan(bitsOf(DEFAULTS));
});

// ---- the meter ----------------------------------------------------------

it("penalises the shapes that make a password weaker than its length claims", () => {
  expect(strength("").label).toBe("empty");
  expect(strength("aaaaaaa").score).toBe(0);
  expect(strength("12345678").hint).toMatch(/only digits/);
  expect(strength("qwertyuiop").hint).toMatch(/qwerty/);
  expect(strength("Password1").score).toBeLessThanOrEqual(1);
  expect(strength("Tr0y-P@ss-Word-9-Cafe").score).toBe(4);
  expect(strength("correct horse battery staple").bits).toBeGreaterThan(90);
  expect(strength("correct horse battery staple").hint).not.toMatch(/detected/);
  expect(poolSize("abc")).toBe(26);
  expect(strength("senha").hint).toMatch(/the word "senha"/);
});

// ---- duplicates ---------------------------------------------------------

it("finds the two kinds of duplication, case-folded on the target", () => {
  const items = [
    openRow(1, 11, "Gmail", "ana", "same-pass", ""),
    openRow(2, 12, "gmail ", " ANA ", "same-pass", ""),
    openRow(3, 11, "VPN", "ana", "other-pass", ""),
    openRow(4, 11, "", "", "", "no target at all"),
  ];
  const d = duplicates(items);
  expect(d.sameTarget).toHaveLength(1);
  expect(d.sameTarget[0].items.map((i) => i.id)).toEqual([1, 2]);
  expect(d.reusedPassword).toHaveLength(1);
  expect(d.reusedPassword[0].items.map((i) => i.id)).toEqual([1, 2]);
  expect(duplicateCount(d)).toBe(2);
  expect(duplicateSummary(d)).toMatch(/reused/);
});

it("says nothing when nothing is duplicated", () => {
  const d = duplicates([openRow(1, 11, "A", "u1", "p1", ""), openRow(2, 11, "B", "u2", "p2", "")]);
  expect(duplicateCount(d)).toBe(0);
  expect(duplicateSummary(d)).toBe("");
});

// ---- auto-lock and settings ----------------------------------------------

it("locks after the quiet, and a touch moves the deadline", () => {
  vi.useFakeTimers();
  let locked = 0;
  const idle = createIdleLock(() => { locked += 1; }, 30_000);
  idle.start();
  vi.advanceTimersByTime(29_000);
  expect(locked).toBe(0);
  expect(idle.remaining()).toBe(1000);
  vi.advanceTimersByTime(1000);
  expect(locked).toBe(1);
  expect(idle.running()).toBe(false);
  idle.stop();
  vi.advanceTimersByTime(60_000);
  expect(locked).toBe(1);
  vi.useRealTimers();
});

it("never accepts a setting that means never lock", () => {
  expect(clampIdleMinutes(0)).toBe(1);
  expect(clampIdleMinutes(-5)).toBe(1);
  expect(clampIdleMinutes(9999)).toBe(60);
  expect(clampIdleMinutes(Number.NaN)).toBe(5);
  writeSettings({ idleMinutes: 0 });
  expect(readSettings().idleMinutes).toBe(1);
  writeSettings({ idleMinutes: 20 });
  expect(readSettings().idleMinutes).toBe(20);
  localStorage.setItem("ponto-cofre-settings", "{not json");
  expect(readSettings().idleMinutes).toBe(5);
  localStorage.setItem("ponto-cofre-settings", JSON.stringify({ idleMinutes: 30, master: "leak" }));
  expect(readSettings()).toEqual({ idleMinutes: 30 });
  expect(idleLabel(1)).toBe("1 min");
  expect(idleLabel(45)).toBe("45 min");
});

// ---- the clipboard ------------------------------------------------------

it("copies, overwrites, and says which one it could not do", async () => {
  const store: string[] = [];
  vi.stubGlobal("navigator", { clipboard: { writeText: async (t: string) => { store.push(t); } } });
  const r = await copySecret("s3cr3t");
  expect(store).toEqual(["s3cr3t"]);
  expect(r.how).toBe("clipboard");
  await scrub();
  expect(store).toEqual(["s3cr3t", "·"]);
  expect(copiedMessage("clipboard")).toMatch(/replaced/);

  vi.stubGlobal("navigator", {});
  expect(canClipboard()).toBe(false);
  const fell = await copySecret("s3cr3t");
  expect(fell.ok).toBe(false);
  expect(copiedMessage("fallback")).toMatch(/keep the value/);
  expect(copiedMessage("none")).toBe("nothing to copy");
  expect((await copySecret("")).ok).toBe(false);
});

// ---- the workspace ------------------------------------------------------

beforeEach(() => vi.stubGlobal("fetch", vi.fn()));

function row(id: number, companyId: number, blob: string, revision = 1): { id: number; companyId: number; blob: string; revision: number; createdAt: string; updatedAt: string } {
  return { id, companyId, blob, revision, createdAt: "", updatedAt: "" };
}

it("locks the moment a row will not open, rather than showing half a vault", async () => {
  const key = await deriveKey(MASTER, SALT, 100_000);
  const check = await sealCheck(key);
  const good = await seal(key, JSON.stringify({ app: "Gmail", user: "ana", pass: "x1", desc: "", company: 11 }), "ponto:vault:item:11");
  const foreign = await seal(key, JSON.stringify({ app: "VPN", user: "", pass: "y2", desc: "", company: 12 }), "ponto:vault:item:12");
  const fetcher = vi.fn(async (url: string) => {
    if (String(url).includes("/api/vault?")) return json({ ...META, check });
    if (String(url).includes("/api/vault/items")) return json({ items: [row(1, 11, good), row(2, 11, foreign)] });
    return json({ ok: true });
  });
  vi.stubGlobal("fetch", fetcher);

  const { result } = renderHook(() => useVaultWorkspace(11));
  const unlocked = await act(async () => await result.current.unlock(MASTER));
  expect(unlocked).toBe(false);
  expect(result.current.items).toHaveLength(0);
  expect(result.current.err).toMatch(/did not open/);
  expect(result.current.phase).toBe("locked");
});

it("opens the list, saves a sealed row, and forgets it on lock", async () => {
  const key = await deriveKey(MASTER, SALT, 100_000);
  const check = await seal(key, "ponto-vault-verifier", "ponto:vault:check");
  const first = await seal(key, JSON.stringify({ app: "Gmail", user: "ana", pass: "x1", desc: "", company: 11 }), "ponto:vault:item:11");
  const store: Record<string, unknown>[] = [row(1, 11, first)];
  const fetcher = vi.fn(async (url: string, init?: { method?: string; body?: string }) => {
    const u = String(url);
    if (init?.method === "POST" && u.endsWith("/api/vault/items?companyId=11")) {
      const body = JSON.parse(String(init.body)) as { companyId: number; blob: string };
      const created = row(2, body.companyId, body.blob);
      store.push(created);
      return json(created);
    }
    if (u.includes("/api/vault/items")) return json({ items: store });
    if (u.includes("/api/vault?")) return json({ ...META, check, items: store.length });
    return json({ ok: true });
  });
  vi.stubGlobal("fetch", fetcher);

  const { result } = renderHook(() => useVaultWorkspace(11));
  await act(async () => { await new Promise((r) => setTimeout(r, 0)); });
  expect(result.current.phase).toBe("locked");

  const ok = await act(async () => await result.current.unlock(MASTER));
  expect(ok).toBe(true);
  expect(result.current.items).toHaveLength(1);
  expect(result.current.items[0].plain.pass).toBe("x1");

  await act(async () => {
    await result.current.save({ app: "VPN", user: "ana", pass: "another-secret-pass", desc: "", companyId: 11 }, 0, 0);
  });
  expect(result.current.items).toHaveLength(2);
  const sentBlob = String((fetcher.mock.calls.at(-1)?.[1] as { body: string }).body);
  expect(sentBlob).not.toContain("another-secret-pass");
  expect(sentBlob).not.toContain("VPN");
  expect(JSON.parse(sentBlob).companyId).toBe(11);
  expect(await open(key, store[1].blob as string, "ponto:vault:item:11")).toMatch(/another-secret-pass/);

  act(() => result.current.lock("closed"));
  expect(result.current.items).toHaveLength(0);
  expect(result.current.phase).toBe("locked");
});

// ---- the little rules ---------------------------------------------------

it("labels, empties and limits the way the list shows them", () => {
  expect(label({ app: "  ", user: "", pass: "", desc: "", companyId: 1 })).toBe("(sin nome)");
  expect(label({ app: "Gmail", user: "ana", pass: "", desc: "", companyId: 1 })).toBe("Gmail · ana");
  expect(blank({ app: " ", user: "  ", pass: "", desc: " ", companyId: 1 })).toBe(true);
  expect(blank({ app: "Gmail", user: "", pass: "", desc: "", companyId: 1 })).toBe(false);
  expect(tooLong({ app: "x".repeat(201), user: "", pass: "", desc: "", companyId: 1 })).toMatch(/aplicativo/);
  expect(tooLong({ app: "", user: "", pass: "", desc: "x".repeat(4001), companyId: 1 })).toMatch(/descrição/);
  expect(tooLong({ app: "ok", user: "", pass: "", desc: "", companyId: 1 })).toBe("");
});

it("refuses a payload with a key nobody designed", () => {
  expect(parsePayload('{"app":"A","user":"u","pass":"p","desc":"d","company":2}')).toEqual({ app: "A", user: "u", pass: "p", desc: "d", companyId: 2 });
  expect(() => parsePayload(JSON.stringify({ app: "x", company: 0 }))).toThrow(/empresa/);
  expect(() => parsePayload(JSON.stringify({ app: "x", company: 1, extra: 1 }))).toThrow(/campo unknown/);
  expect(() => parsePayload(JSON.stringify({ app: 1, company: 1 }))).toThrow(/'app' não texto/);
  expect(() => parsePayload(JSON.stringify({ app: "x".repeat(201), company: 1 }))).toThrow(/aplicativo/);
  expect(() => parsePayload("")).toThrow(/record empty/);
  expect(() => parsePayload("[]")).toThrow(/não objeto/);
});
