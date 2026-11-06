import { expect, test } from "@playwright/test";

test("two tabs select independent companies and keep that choice after reloading", async ({ browser, request }) => {
  const catalog = await (await request.get("/api/companies")).json();
  const other = await (await request.post("/api/companies?companyId=" + catalog.activeId, { data: { name: "Empresa navegador" } })).json();
  const first = await browser.newContext(), second = await browser.newContext();
  try {
    const a = await first.newPage(), b = await second.newPage();
    await a.goto("/"); await b.goto("/");
    const selectorA = a.getByLabel("Empresa ativa"), selectorB = b.getByLabel("Empresa ativa");
    await selectorB.selectOption(String(other.id));
    await expect(selectorB).toHaveValue(String(other.id));
    await expect(selectorA).toHaveValue(String(catalog.activeId));
    await b.reload(); await expect(b.getByLabel("Empresa ativa")).toHaveValue(String(other.id));
    await expect(selectorA).toHaveValue(String(catalog.activeId));
  } finally { await first.close(); await second.close(); }
});

test("a failed note save keeps editing and the navigation dialog traps keyboard focus", async ({ page, request }) => {
  const catalog = await (await request.get("/api/companies")).json();
  const note = await (await request.post("/api/notes?companyId=" + catalog.activeId, { data: { title: "Falha de rede", body: "original" } })).json();
  await page.goto("/notas/" + note.id);
  await page.getByPlaceholder("Sem título").fill("rascunho importante");
  await page.route("**/api/notes/*?*", async route => {
    if (route.request().method() === "PUT") await route.fulfill({ status: 503, json: { error: "sem conexão" } });
    else await route.continue();
  });
  await page.getByRole("button", { name: "Salvar", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("sem conexão");
  await page.getByRole("link", { name: "Registros", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.keyboard.press("Tab");
  await expect(page.getByRole("button", { name: "Continuar editando" })).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByPlaceholder("Sem título")).toHaveValue("rascunho importante");
  await expect(page).toHaveURL(/\/notas\//);
});

test("conflicting note revisions preserve the draft and allow downloading it", async ({ page, request }) => {
  const catalog = await (await request.get("/api/companies")).json();
  const suffix = "?companyId=" + catalog.activeId;
  const note = await (await request.post("/api/notes" + suffix, { data: { title: "Conflito", body: "original" } })).json();
  await page.goto("/notas/" + note.id);
  await expect(page.getByPlaceholder("Sem título")).toHaveValue("Conflito");
  await request.put("/api/notes/" + note.id + suffix, { data: { title: "outra aba", body: "mais recente", revision: note.revision } });
  await page.getByPlaceholder("Sem título").fill("meu rascunho");
  await page.getByRole("button", { name: "Salvar", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("outro acesso");
  await expect(page.getByPlaceholder("Sem título")).toHaveValue("meu rascunho");
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Baixar meu rascunho" }).click();
  expect((await download).suggestedFilename()).toBe("rascunho-nota.txt");
  const stored = await (await request.get("/api/notes/" + note.id + suffix)).json();
  expect(stored.body).toBe("mais recente");
});

test("mobile navigation exposes the form without horizontal overflow", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await page.getByRole("button", { name: /^Menu/ }).click();
  await page.getByRole("link", { name: "Novo registro", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Novo registro", exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test("the installed shell loads offline and tells the user the connection was lost", async ({ page, context }) => {
  await page.goto("/");
  await page.evaluate(async () => { await navigator.serviceWorker.ready; });
  await expect.poll(() => page.evaluate(() => Boolean(navigator.serviceWorker.controller))).toBe(true);
  await context.setOffline(true);
  await page.reload();
  await expect(page.getByText(/Sem conexão\. Suas alterações/)).toBeVisible();
  await context.setOffline(false);
});
