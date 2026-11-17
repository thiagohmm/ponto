import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeAll, expect, it, vi } from "vitest";
import { webcrypto } from "node:crypto";
import { CofrePage } from "../src/pages/Cofre";
import { api } from "../src/api";
import { deriveKey, aadItem, seal, sealCheck } from "../src/vault/crypto";

/**
 * The cofre screen. What matters here is not that React renders — it is that the secret does not
 * reach the tree in the clear unless the user asked for it, and that a refused unlock shows a
 * refusal instead of an empty list that looks like "you have nothing".
 *
 * The row's hidden read-only input is asserted through the DOM rather than by display value,
 * because the point is the attribute, not the string: `aria-hidden` keeps a screen reader from
 * reading a password character by character, and `type="password"` keeps it off the page's
 * copyable text. The value is in the element on purpose — that is what the Copy button reads.
 *
 * Row buttons are queried inside `.cofre-row`, because the toolbar has "Hide all" and a test
 * that clicked the first button matching "Hide" would be testing the toolbar by accident.
 */

vi.mock("../src/api", () => ({
  api: {
    vault: vi.fn(),
    sealVault: vi.fn(),
    vaultItems: vi.fn(),
    createVaultItem: vi.fn(),
    updateVaultItem: vi.fn(),
    removeVaultItem: vi.fn(),
    wipeVaultCompany: vi.fn(),
    vaultRekey: vi.fn(),
    importVaultItems: vi.fn(),
  },
  selectApiCompany: vi.fn(),
}));

vi.mock("../src/Company", () => ({
  useCompany: () => ({ active: { id: 11, name: "Acme" }, companies: [{ id: 11, name: "Acme" }, { id: 12, name: "Beta" }], reload: vi.fn(), select: vi.fn() }),
  CompanyBar: () => null,
}));

const MASTER = "café-π 123";
const SALT = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

beforeAll(() => vi.stubGlobal("crypto", webcrypto));
afterEach(cleanup);

async function meta(sealed = true) {
  const key = await deriveKey(MASTER, SALT, 100_000);
  return { sealed, kdf: "PBKDF2-SHA256", salt: SALT, iterations: 100_000, check: sealed ? await sealCheck(key) : "", items: 0, updatedAt: "" };
}

async function record(id: number, companyId: number, app: string, user: string, pass: string) {
  const key = await deriveKey(MASTER, SALT, 100_000);
  return { id, companyId, revision: 1, createdAt: "", updatedAt: "", blob: await seal(key, JSON.stringify({ app, user, pass, desc: "", company: companyId }), aadItem(companyId)) };
}

function open(master = MASTER) {
  const field = screen.getByPlaceholderText("••••••••") as HTMLInputElement;
  fireEvent.change(field, { target: { value: master } });
  fireEvent.click(screen.getByRole("button", { name: "Open" }));
}

function row() {
  return document.querySelector(".cofre-row") as HTMLElement;
}

function rowButton(label: string) {
  const b = [...row().querySelectorAll("button")].find((x) => x.textContent?.trim() === label);
  if (!b) throw new Error(`no "${label}" button in the row`);
  return b;
}

function secret() {
  return row().querySelector("input") as HTMLInputElement;
}

it("asks for a master password before any list exists", async () => {
  vi.mocked(api.vault).mockResolvedValue(await meta(false));
  render(<CofrePage />);
  await screen.findByRole("heading", { name: "Create the cofre" });
  expect(screen.queryByText(/nothing yet/)).toBeNull();
  expect(document.querySelector(".cofre-row")).toBeNull();
});

it("refuses a wrong master password and shows the refusal, not an empty vault", async () => {
  vi.mocked(api.vault).mockResolvedValue(await meta(true));
  vi.mocked(api.vaultItems).mockResolvedValue({ items: [] });
  render(<CofrePage />);
  await screen.findByRole("heading", { name: "Unlock the cofre" });
  open("wrong-password");
  await screen.findByText(/wrong master password/);
  expect(screen.queryByText(/nothing yet/)).toBeNull();
});

it("opens on Enter, and lists the app and user without the password", async () => {
  vi.mocked(api.vault).mockResolvedValue(await meta(true));
  vi.mocked(api.vaultItems).mockResolvedValue({ items: [await record(1, 11, "Gmail", "ana", "alpha-secret-one")] });
  render(<CofrePage />);
  await screen.findByRole("heading", { name: "Unlock the cofre" });
  const field = screen.getByPlaceholderText("••••••••") as HTMLInputElement;
  fireEvent.change(field, { target: { value: MASTER } });
  fireEvent.keyDown(field, { key: "Enter" });

  await screen.findByText("Gmail");
  expect(screen.getByText("ana")).toBeTruthy();
  expect(rowButton("Copy")).toBeTruthy();

  const s = secret();
  expect(s.getAttribute("aria-hidden")).toBe("true");
  expect(s.type).toBe("password");
  expect(s.tabIndex).toBe(-1);
  expect(s.value).toBe("alpha-secret-one");
});

it("reveals and hides on request, and copies through the clipboard", async () => {
  const store: string[] = [];
  vi.stubGlobal("navigator", { clipboard: { writeText: async (t: string) => { store.push(t); } } });
  vi.mocked(api.vault).mockResolvedValue(await meta(true));
  vi.mocked(api.vaultItems).mockResolvedValue({ items: [await record(1, 11, "VPN", "ana", "beta-secret-two")] });
  render(<CofrePage />);
  await screen.findByRole("heading", { name: "Unlock the cofre" });
  open();
  await screen.findByText("VPN");

  fireEvent.click(rowButton("Show"));
  expect(secret().type).toBe("text");

  fireEvent.click(rowButton("Copy"));
  await waitFor(() => expect(store).toEqual(["beta-secret-two"]));
  expect(await screen.findByText(/replaced/)).toBeTruthy();

  fireEvent.click(rowButton("Hide"));
  await waitFor(() => expect(secret().type).toBe("password"));
});

it("copies without opening the row — the clipboard already has it", async () => {
  const store: string[] = [];
  vi.stubGlobal("navigator", { clipboard: { writeText: async (t: string) => { store.push(t); } } });
  vi.mocked(api.vault).mockResolvedValue(await meta(true));
  vi.mocked(api.vaultItems).mockResolvedValue({ items: [await record(1, 11, "VPN", "ana", "beta-secret-two")] });
  render(<CofrePage />);
  await screen.findByRole("heading", { name: "Unlock the cofre" });
  open();
  await screen.findByText("VPN");

  fireEvent.click(rowButton("Copy"));
  await waitFor(() => expect(store).toEqual(["beta-secret-two"]));
  expect(secret().type).toBe("password");
  expect(await screen.findByText(/replaced/)).toBeTruthy();

  // then open it deliberately, and the same button now closes it
  fireEvent.click(rowButton("Show"));
  expect(secret().type).toBe("text");
});

it("says when a password is reused across targets", async () => {
  vi.mocked(api.vault).mockResolvedValue(await meta(true));
  vi.mocked(api.vaultItems).mockResolvedValue({
    items: [await record(1, 11, "Gmail", "ana", "same-pass-here"), await record(2, 12, "GitLab", "ana", "same-pass-here")],
  });
  render(<CofrePage />);
  await screen.findByRole("heading", { name: "Unlock the cofre" });
  open();
  await waitFor(() => expect(document.querySelector(".cofre-warn")).toBeTruthy());
  const warn = document.querySelector(".cofre-warn") as HTMLElement;
  expect(warn.textContent).toMatch(/reused/);
  expect(warn.getAttribute("role")).toBe("status");
});

it("seals a new row through the API, and the body carries no plaintext", async () => {
  vi.mocked(api.vault).mockResolvedValue(await meta(true));
  vi.mocked(api.vaultItems).mockResolvedValue({ items: [] });
  vi.mocked(api.createVaultItem).mockResolvedValue({ id: 9, companyId: 11, blob: "ignored=", revision: 1, createdAt: "", updatedAt: "" });
  render(<CofrePage />);
  await screen.findByRole("heading", { name: "Unlock the cofre" });
  open();
  await screen.findByText(/nothing yet/);

  fireEvent.change(screen.getByLabelText("Application"), { target: { value: "Router" } });
  fireEvent.change(screen.getByLabelText("User"), { target: { value: "admin" } });
  fireEvent.change(screen.getByLabelText("Password"), { target: { value: "gamma-secret-three" } });
  fireEvent.click(screen.getByRole("button", { name: "Save" }));

  await waitFor(() => expect(api.createVaultItem).toHaveBeenCalled());
  const sent = vi.mocked(api.createVaultItem).mock.calls[0][0];
  expect(sent.companyId).toBe(11);
  expect(JSON.stringify(sent)).not.toContain("gamma-secret-three");
  expect(await screen.findByText(/saved/)).toBeTruthy();
});

it("locks from the toolbar and the gate comes back", async () => {
  vi.mocked(api.vault).mockResolvedValue(await meta(true));
  vi.mocked(api.vaultItems).mockResolvedValue({ items: [await record(1, 11, "Gmail", "ana", "alpha-secret-one")] });
  render(<CofrePage />);
  await screen.findByRole("heading", { name: "Unlock the cofre" });
  open();
  await screen.findByText("Gmail");
  fireEvent.click(screen.getByRole("button", { name: "Lock" }));
  expect(await screen.findByRole("heading", { name: "Unlock the cofre" })).toBeTruthy();
  expect(document.querySelector(".cofre-row")).toBeNull();
});
