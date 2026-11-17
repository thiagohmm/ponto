/**
 * The cofre's shapes: what a record is, what its plaintext looks like, what is too big.
 *
 * `parsePayload` mirrors `VaultPayload.parse` in Java field for field. That mirror is not
 * decoration: a record sealed by the CLI has to open in the browser and vice versa, and an
 * imported file is untrusted input — a key nobody designed is refused rather than kept
 * quiet, because a silent extra field is how a leak grows.
 */

import type { VaultRecord } from "../types";

/** The plaintext inside one blob: the four fields a person typed, plus the company tag. */
export type VaultPlain = {
  app: string;
  user: string;
  pass: string;
  desc: string;
  companyId: number;
};

export const APP_MAX = 200;
export const USER_MAX = 200;
export const PASS_MAX = 512;
export const DESC_MAX = 4_000;

/** A decrypted row: the row itself plus what it held. Only ever in memory. */
export type VaultOpen = VaultRecord & { plain: VaultPlain };

export class VaultFormatError extends Error {}

/** The only keys a payload may carry — the same list as `VaultPayload.KEYS`. */
const KEYS = ["app", "user", "pass", "desc", "company"] as const;

export function payloadJson(p: VaultPlain): string {
  return JSON.stringify({ app: p.app, user: p.user, pass: p.pass, desc: p.desc, company: p.companyId });
}

/** Strict on purpose: an imported file or a paste may not smuggle a fifth field. */
export function parsePayload(text: string): VaultPlain {
  if (!text || !text.trim()) throw new VaultFormatError("cofre: record empty");
  let tree: unknown;
  try {
    tree = JSON.parse(text);
  } catch {
    throw new VaultFormatError("cofre: JSON corrupto");
  }
  if (typeof tree !== "object" || tree === null || Array.isArray(tree)) throw new VaultFormatError("cofre: record não objeto");
  const obj = tree as Record<string, unknown>;
  for (const key of Object.keys(obj)) {
    if (!KEYS.includes(key as (typeof KEYS)[number])) throw new VaultFormatError(`cofre: campo unknown '${key}'`);
  }
  const text0 = (key: "app" | "user" | "pass" | "desc"): string => {
    const value = obj[key];
    if (value === undefined || value === null) return "";
    if (typeof value !== "string") throw new VaultFormatError(`cofre: '${key}' não texto`);
    return value;
  };
  const company = obj["company"];
  if (typeof company !== "number" || !Number.isInteger(company) || company < 1) {
    throw new VaultFormatError("cofre: empresa invalida");
  }
  const bound = (value: string, max: number, what: string): string => {
    if (value.length > max) throw new VaultFormatError(`cofre: ${what} mais longa que ${max} chars`);
    return value;
  };
  return {
    app: bound(text0("app"), APP_MAX, "aplicativo").trim(),
    user: bound(text0("user"), USER_MAX, "usuario").trim(),
    pass: bound(text0("pass"), PASS_MAX, "senha"),
    desc: bound(text0("desc"), DESC_MAX, "descrição").trim(),
    companyId: company,
  };
}

/** "Gmail · ana" — the line the list shows, the same one the terminal prints. */
export function label(p: VaultPlain): string {
  const head = p.app.trim() === "" ? "(sin nome)" : p.app.trim();
  const user = p.user.trim();
  return user === "" ? head : head + " · " + user;
}

export function blank(p: VaultPlain): boolean {
  return !p.app.trim() && !p.user.trim() && !p.pass && !p.desc.trim();
}

/** What the form may send: the same limits, so a 400 never arrives after the spinner. */
export function tooLong(p: VaultPlain): string {
  if (p.app.length > APP_MAX) return `aplicativo: até ${APP_MAX} characters`;
  if (p.user.length > USER_MAX) return `usuario: até ${USER_MAX} characters`;
  if (p.pass.length > PASS_MAX) return `senha: até ${PASS_MAX} characters`;
  if (p.desc.length > DESC_MAX) return `descrição: até ${DESC_MAX} characters`;
  return "";
}

/** Sort the way a person scans a list: by name, ignoring case and accents. */
export function byLabel(a: VaultOpen, b: VaultOpen): number {
  return label(a.plain).localeCompare(label(b.plain), "pt-BR", { sensitivity: "base" });
}
