import { afterEach, expect, it, vi } from "vitest";
import { api, selectApiCompany } from "../src/api";

afterEach(() => vi.unstubAllGlobals());
it("binds each request and attachment/export URL to its company", async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response("{}", { status: 200 }));
  vi.stubGlobal("fetch", fetcher);
  selectApiCompany(11);
  const first = api.entries({});
  selectApiCompany(22);
  await first;
  expect(fetcher.mock.calls[0][0]).toBe("/api/entries?companyId=11");
  expect(api.exportUrl("csv", "2026-01-01", "2026-01-31")).toContain("&companyId=22");
  expect(api.fileDownloadUrl(5, 7)).toBe("/api/notes/5/files/7/download?companyId=22");
});
it("bootstraps the company catalog without reusing a stale selection", async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response("{}", { status: 200 }));
  vi.stubGlobal("fetch", fetcher);
  selectApiCompany(999);
  await api.companies();
  expect(fetcher.mock.calls[0][0]).toBe("/api/companies");
});
it("retains confirmation codes and exposes request IDs on service failures", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ need: "overlap", message: "sobreposição" }), { status: 409 })));
  await expect(api.create({ dia: "2026-05-20", tin: "08:00", tout: "09:00", just: "" })).rejects.toMatchObject({ need: "overlap", status: 409 });
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: "falha", requestId: "trace-123" }), { status: 503 })));
  await expect(api.home()).rejects.toThrow("trace-123");
});
