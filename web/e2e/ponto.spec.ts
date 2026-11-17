import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

const USER = "e2e";
const PASS = "e2e-secret";

async function apiLogin(request: APIRequestContext) {
  const r = await request.post("/api/login", { data: { user: USER, password: PASS } });
  expect(r.ok()).toBeTruthy();
}

async function uiLogin(page: Page) {
  await page.goto("/login");
  await page.getByLabel("Usuário").fill(USER);
  await page.getByLabel("Senha").fill(PASS);
  await page.getByRole("button", { name: "Entrar" }).click();
  await expect(page.getByRole("heading", { name: "Registros" })).toBeVisible();
}

test("two tabs select independent companies and keep that choice after reloading", async ({ browser, request }) => {
  await apiLogin(request);
  const catalog = await (await request.get("/api/companies")).json();
  const other = await (await request.post("/api/companies?companyId=" + catalog.activeId, { data: { name: "Empresa navegador" } })).json();
  const first = await browser.newContext(), second = await browser.newContext();
  try {
    const a = await first.newPage(), b = await second.newPage();
    await uiLogin(a); await uiLogin(b);
    const selectorA = a.getByLabel("Empresa ativa"), selectorB = b.getByLabel("Empresa ativa");
    await selectorB.selectOption(String(other.id));
    await expect(selectorB).toHaveValue(String(other.id));
    await expect(selectorA).toHaveValue(String(catalog.activeId));
    await b.reload(); await expect(b.getByLabel("Empresa ativa")).toHaveValue(String(other.id));
    await expect(selectorA).toHaveValue(String(catalog.activeId));
  } finally { await first.close(); await second.close(); }
});

test("a failed note save keeps editing and the navigation dialog traps keyboard focus", async ({ page, request }) => {
  await apiLogin(request);
  const catalog = await (await request.get("/api/companies")).json();
  const note = await (await request.post("/api/notes?companyId=" + catalog.activeId, { data: { title: "Falha de rede", body: "original" } })).json();
  await uiLogin(page);
  await page.goto("/notas/" + note.id);
  await expect(page.getByPlaceholder("Sem título")).toHaveValue("Falha de rede");
  await page.route("**/api/notes/*?*", async route => {
    if (route.request().method() === "PUT") await route.fulfill({ status: 503, json: { error: "sem conexão" } });
    else await route.continue();
  });
  await page.getByPlaceholder("Sem título").fill("rascunho importante");
  await expect(page.getByRole("button", { name: "Salvar", exact: true })).toBeEnabled();
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
  await apiLogin(request);
  const catalog = await (await request.get("/api/companies")).json();
  const suffix = "?companyId=" + catalog.activeId;
  const note = await (await request.post("/api/notes" + suffix, { data: { title: "Conflito", body: "original" } })).json();
  await uiLogin(page);
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
  await uiLogin(page);
  await page.getByRole("button", { name: /^Menu/ }).click();
  await page.getByRole("link", { name: "Novo registro", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Novo registro", exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test("the installed shell loads offline and tells the user the connection was lost", async ({ page, context }) => {
  await uiLogin(page);
  await page.evaluate(async () => { await navigator.serviceWorker.ready; });
  await expect.poll(() => page.evaluate(() => Boolean(navigator.serviceWorker.controller))).toBe(true);
  await context.setOffline(true);
  await page.reload();
  await expect(page.getByText(/Sem conexão\. Suas alterações/)).toBeVisible();
  await context.setOffline(false);
});

test("diario page shows day picker and can generate a saved summary", async ({ page, request }) => {
  await apiLogin(request);
  await uiLogin(page);
  await page.getByRole("link", { name: "Diário", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Diário" })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Dia" })).toBeVisible();
  await expect(page.getByRole("button", { name: /Gerar resumo|Regenerar resumo/ })).toBeEnabled();
  await page.getByRole("button", { name: /Gerar resumo|Regenerar resumo/ }).click();
  await expect(page.getByText(/salvo|Resumo salvo/i).first()).toBeVisible({ timeout: 15_000 });
});

test("login is required before opening the workspace", async ({ page, request }) => {
  expect((await request.get("/api/companies")).status()).toBe(401);
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Entrar" })).toBeVisible();
  await page.getByLabel("Usuário").fill(USER);
  await page.getByLabel("Senha").fill("wrong");
  await page.getByRole("button", { name: "Entrar" }).click();
  await expect(page.getByRole("alert")).toBeVisible();
  await page.getByLabel("Senha").fill(PASS);
  await page.getByRole("button", { name: "Entrar" }).click();
  await expect(page.getByRole("heading", { name: "Registros" })).toBeVisible();
});

test("the cofre opens with a master password and never puts a secret in the page text", async ({ page, request }) => {
  await apiLogin(request);
  const catalog = await (await request.get("/api/companies")).json();
  const suffix = "?companyId=" + catalog.activeId;
  const master = "e2e master café-π 12345";
  const secret = "e2e-s3cr3t-Alpha";

  await uiLogin(page);
  await page.goto("/cofre");
  await expect(page.getByRole("heading", { name: "Create the cofre" })).toBeVisible();

  await page.locator(".cofre-gate").getByPlaceholder("••••••••").fill(master);
  await page.locator(".cofre-gate").getByRole("button", { name: "Create and open" }).click();
  await expect(page.getByText(/nothing yet/)).toBeVisible({ timeout: 20_000 });

  // scoped to the form: elsewhere on the page a row waits to be copied and the
  // backup panel has its own "Save sealed file" button
  const form = page.locator(".cofre-form");
  await form.getByLabel("Application").fill("Router e2e");
  await form.getByLabel("User").fill("admin");
  await form.getByLabel("Password").fill(secret);
  await form.getByRole("button", { name: "Save" }).click();
  await expect(page.getByText(/saved/i).first()).toBeVisible();

  // the row shows the target, not the secret
  await expect(page.getByText("Router e2e")).toBeVisible();
  const raw = await (await request.get("/api/vault/items" + suffix)).json();
  expect(raw.items).toHaveLength(1);
  expect(raw.items[0].blob).not.toContain(secret);
  expect(raw.items[0].blob).not.toContain("Router");

  // the page never carries the secret as visible text; the input holds it to be copied
  expect(await page.evaluate((want) => document.body.innerText.includes(want), secret)).toBe(false);
  const input = page.locator(".cofre-secret input");
  expect(await input.getAttribute("aria-hidden")).toBe("true");
  expect(await input.getAttribute("type")).toBe("password");

  await page.locator(".cofre-row").getByRole("button", { name: "Copy" }).click();
  await expect(page.getByText(/replaced|keep the value/i).first()).toBeVisible();
  await page.locator(".cofre-bar").getByRole("button", { name: "Lock" }).click();
  await expect(page.getByRole("heading", { name: "Unlock the cofre" })).toBeVisible();

  await page.locator(".cofre-gate").getByPlaceholder("••••••••").fill("wrong master");
  await page.locator(".cofre-gate").getByRole("button", { name: "Open" }).click();
  await expect(page.getByText(/wrong master password/i)).toBeVisible();
});
