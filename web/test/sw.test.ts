// @vitest-environment node
import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";
import { expect, it, vi } from "vitest";

function worker() {
  const handlers: Record<string, (event: any) => void> = {};
  const cache = { addAll: vi.fn().mockResolvedValue(undefined), match: vi.fn(), put: vi.fn() };
  const caches = { open: vi.fn().mockResolvedValue(cache), keys: vi.fn().mockResolvedValue(["other-app", "ponto-old"]), delete: vi.fn().mockResolvedValue(true) };
  const self = { registration: { active: {} }, location: { origin: "https://ponto.test" },
    skipWaiting: vi.fn(), clients: { claim: vi.fn() }, addEventListener: (name: string, fn: (event: any) => void) => { handlers[name] = fn; } };
  const fetcher = vi.fn().mockRejectedValue(new Error("offline"));
  const source = readFileSync(new URL("../public/sw.js", import.meta.url), "utf8").replace("__BUILD__", "test").replace("__ASSETS__", "[]");
  runInNewContext(source, { self, caches, fetch: fetcher, Response, URL });
  return { self, cache, caches, handlers };
}

it("waits for user consent to activate an update and preserves other apps' caches", async () => {
  const w = worker();
  let completion!: Promise<unknown>;
  w.handlers.install({ waitUntil: (value: Promise<unknown>) => { completion = value; } });
  await completion;
  expect(w.self.skipWaiting).not.toHaveBeenCalled();
  w.handlers.message({ data: { type: "ACTIVATE" } });
  expect(w.self.skipWaiting).toHaveBeenCalledOnce();
  w.handlers.activate({ waitUntil: (value: Promise<unknown>) => { completion = value; } });
  await completion;
  expect(w.caches.delete).toHaveBeenCalledExactlyOnceWith("ponto-old");
});
it("returns a network error for API reads without caching personal data", async () => {
  const w = worker();
  let response!: Promise<Response>;
  w.handlers.fetch({ request: { method: "GET", url: "https://ponto.test/api/notes?companyId=1" },
    respondWith: (value: Promise<Response>) => { response = value; } });
  expect((await response).status).toBe(503);
  expect(w.caches.open).not.toHaveBeenCalled();
});
