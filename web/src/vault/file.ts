/**
 * The backup file — sealed, and useless to anyone without the master password.
 *
 * One blob wraps the whole list, rather than one blob per record inside the file. The
 * reason is integrity: AES-GCM's tag then covers the entire set, so deleting a record,
 * swapping two, or appending a forged one makes the file refuse to open. A file of
 * independently sealed records would accept that edit silently — each blob would still
 * open on its own, and a backup that cannot tell it was edited is not a backup.
 *
 * The header (`kdf`, `salt`, `iterations`, `count`) is in the clear on purpose. Those are
 * public parameters, not secrets: the salt only matters as a defence against a rainbow
 * table built against the *server's* database, which is a different file. Keeping them here
 * buys one real convenience — a backup taken before a re-key still opens with the same master
 * password, because the file carries the salt it was sealed with.
 *
 * `count` is also authenticated: it is the export AAD, so a header edited to match a
 * trimmed list breaks the tag. Plaintext exports are refused, and said out loud: a vault
 * that would import a CSV of passwords is a vault that stores passwords somewhere in the
 * clear.
 */

import { ITERATIONS, KDF, MAX_BLOB_CHARS, aadExport, aadItem, deriveKey, open, seal } from "./crypto";
import { parsePayload, payloadJson, type VaultOpen, type VaultPlain } from "./model";

export const FORMAT = "ponto-cofre/1";

export type VaultFile = {
  format: string;
  app: string;
  exportedAt: string;
  kdf: string;
  salt: string;
  iterations: number;
  /** How many records are inside. Authenticated: it is the export's AAD. */
  count: number;
  /** base64(iv ‖ ciphertext ‖ tag) over the whole list. */
  sealed: string;
};

/** One row of the decrypted list: a company tag, a sealed blob, a revision. */
type SealedRow = { companyId: number; blob: string; revision: number };

/**
 * Seal the vault into a file.
 *
 * Each record is re-sealed here with the caller's key and its own company AAD, then the
 * whole list is sealed again. The double seal is not paranoia: the inner one keeps the
 * per-company binding inside the file, so an attacker who could swap a blob between
 * companies still could not, and the outer one is what makes the file atomic.
 */
export async function exportFile(items: VaultOpen[], passphrase: string, salt: string, iterations: number = ITERATIONS): Promise<Blob> {
  if (!salt) throw new Error("cofre: no salt to seal the backup with.");
  const key = await deriveKey(passphrase, salt, iterations);
  const rows: SealedRow[] = [];
  for (const item of items) {
    const companyId = item.plain.companyId;
    const blob = await seal(key, payloadJson({ ...item.plain, companyId }), aadItem(companyId));
    rows.push({ companyId, blob, revision: item.revision });
  }
  const sealed = await seal(key, JSON.stringify(rows), aadExport(rows.length));
  if (sealed.length > MAX_BLOB_CHARS * 8) {
    throw new Error("cofre: backup larger than the app can hold in one file — export one company at a time.");
  }
  const file: VaultFile = {
    format: FORMAT,
    app: "ponto",
    exportedAt: new Date().toISOString(),
    kdf: KDF,
    salt,
    iterations,
    count: rows.length,
    sealed,
  };
  return new Blob([`${JSON.stringify(file, null, 2)}\n`], { type: "application/json" });
}

/** Open a backup. Returns plaintext records, which the page re-seals on import. */
export async function readVaultFile(text: string, passphrase: string): Promise<VaultPlain[]> {
  const file = shape(text);
  const key = await deriveKey(passphrase, file.salt, file.iterations);
  const plain = await open(key, file.sealed, aadExport(file.count));
  let rows: unknown;
  try {
    rows = JSON.parse(plain);
  } catch {
    throw new Error("cofre: the backup's list is not readable.");
  }
  if (!Array.isArray(rows)) throw new Error("cofre: the backup's list is not a list.");
  if (rows.length !== file.count) throw new Error(`cofre: the backup says ${file.count} records and holds ${rows.length}.`);
  return Promise.all(rows.map((row) => rowOf(key, row)));
}

// ---- the reading side ----------------------------------------------------

/** Header checks first, so a wrong file is named instead of blamed on the password. */
function shape(text: string): VaultFile {
  if (looksLikePlaintextList(text)) {
    throw new Error("cofre: this file holds passwords in the clear. The cofre only imports sealed backups.");
  }
  let tree: unknown;
  try {
    tree = JSON.parse(text);
  } catch {
    throw new Error("cofre: this is not a backup file (the JSON does not parse).");
  }
  if (typeof tree !== "object" || tree === null || Array.isArray(tree)) throw new Error("cofre: this is not a backup file.");
  const f = tree as Partial<VaultFile>;
  if (f.format !== FORMAT) throw new Error(`cofre: backup format "${String(f.format)}" — this app writes ${FORMAT}.`);
  if (f.kdf !== KDF) throw new Error(`cofre: backup uses "${String(f.kdf)}" — ponto only speaks ${KDF}.`);
  if (typeof f.salt !== "string" || f.salt === "") throw new Error("cofre: backup has no salt.");
  if (typeof f.iterations !== "number") throw new Error("cofre: backup has no iteration count.");
  if (typeof f.count !== "number" || f.count < 0) throw new Error("cofre: backup has no record count.");
  if (typeof f.sealed !== "string" || f.sealed === "") throw new Error("cofre: backup has no sealed list.");
  return { format: FORMAT, app: f.app ?? "", exportedAt: f.exportedAt ?? "", kdf: KDF, salt: f.salt, iterations: f.iterations, count: f.count, sealed: f.sealed };
}

/** One row: untrusted input, so every field is checked before anything reaches the form. */
async function rowOf(key: CryptoKey, row: unknown): Promise<VaultPlain> {
  if (typeof row !== "object" || row === null) throw new Error("cofre: backup record is not an object.");
  const r = row as Partial<SealedRow>;
  if (typeof r.blob !== "string" || r.blob === "") throw new Error("cofre: backup record has no blob.");
  if (typeof r.companyId !== "number" || !Number.isInteger(r.companyId) || r.companyId < 1) {
    throw new Error("cofre: backup record has no company.");
  }
  // Opened in its own company's context, then parsed by the same strict parser the live
  // list uses. A record that cannot import is named by its company, not skipped quietly.
  const text = await open(key, r.blob, aadItem(r.companyId)).catch(() => {
    throw new Error(`cofre: the backup's record for company ${r.companyId} did not open.`);
  });
  return { ...parsePayload(text), companyId: r.companyId };
}

/** Refuse a plaintext list before deriving anything: cheaper, and a clearer message. */
export function looksLikePlaintextList(text: string): boolean {
  const tree = safeJson(text);
  if (!Array.isArray(tree)) return false;
  return tree.some((row) => typeof row === "object" && row !== null && "pass" in (row as Record<string, unknown>));
}

/** Refuse an unsealed object that only *looks* like a backup. */
function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

/** The download's name: dated, so a folder of backups still sorts usefully. */
export function fileName(now = new Date()): string {
  return `ponto-cofre-${now.toISOString().slice(0, 19).replace(/[:.]/g, "-")}.json`;
}
