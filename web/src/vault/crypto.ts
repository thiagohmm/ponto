/**
 * The cofre's cryptography — and the reason the server never sees a password.
 *
 * This file is the browser half of a contract with `src/main/java/ponto/model/VaultCrypto.java`.
 * Both sides derive PBKDF2-SHA256 over the UTF-8 *bytes* of the master password and seal
 * with AES-256-GCM, writing the same envelope: `base64(iv(12) ‖ ciphertext ‖ tag(16))`.
 * If the two halves ever disagree, every vault in production opens garbage — so the
 * numbers below are pinned by tests on both sides (`VaultCryptoTest` on the Java side,
 * `vaultCrypto.test.ts` here) against a fixed vector: the same master password, salt and
 * iteration count must produce the same key bytes in both languages.
 *
 * What the master password never does: leave the browser. `deriveKey` runs here, the
 * `CryptoKey` stays in memory, and it is not extractable — no other script on the page,
 * no extension, no `window.crypto` hook can pull the bytes out of it. The server receives
 * only sealed blobs. A stolen database is a pile of base64 nobody can open.
 *
 * Two consequences of that design are deliberate and worth stating:
 *
 * - A wrong master password is invisible to the server. It cannot log failures, cannot
 *   rate-limit guesses, and cannot help a user who forgot it. There is no recovery, by
 *   construction; the UI says so instead of pretending otherwise.
 * - The company tag is authenticated, not encrypted: it travels as plaintext so the API
 *   can filter and count, while the record's contents stay sealed and are bound to that
 *   tag by AAD. Relabelling a record breaks its own tag, so the pair cannot be forged.
 */

export const KDF = "PBKDF2-SHA256";
/** Lower bound the server will accept; the default is what a 2026 laptop can do in ~0.3 s. */
export const ITERATIONS = 310_000;
export const MIN_ITERATIONS = 100_000;
export const MAX_ITERATIONS = 5_000_000;
export const SALT_BYTES = 32;
export const IV_BYTES = 12;
export const TAG_BYTES = 16;
/** Same ceiling as the Java store, so a record is refused here before a 413 round trip. */
export const MAX_BLOB_CHARS = 65_536;

/** Verifier text inside the vault's own blob; a wrong key cannot produce it. */
export const CHECK_TEXT = "ponto-vault-verifier";

const encoder = new TextEncoder();
const decoder = new TextDecoder("utf-8", { fatal: true });

/**
 * Why the cofre cannot run here, or `null` when it can.
 * HTTP on a LAN IP is the usual case: Chrome still has WebCrypto, but it withholds
 * `crypto.subtle` outside a secure context (HTTPS or localhost).
 */
export type VaultCryptoBlock = "insecure" | "missing";

type CryptoGlobal = { crypto?: Crypto; isSecureContext?: boolean };

export const INSECURE_CONTEXT_MSG =
  "O cofre precisa de HTTPS (ou localhost). Em HTTP da rede local o browser não libera WebCrypto — abra pela URL HTTPS do túnel.";

export const MISSING_WEBCRYPTO_MSG =
  "Este browser não tem WebCrypto. Use Chrome, Edge, Firefox ou Safari recente — o cofre não guarda keys em texto.";

/** `null` when sealing can run; otherwise the reason the Gate should refuse the form. */
export function vaultCryptoBlockReason(): VaultCryptoBlock | null {
  const g = globalThis as CryptoGlobal;
  if (g.isSecureContext === false) return "insecure";
  const c = g.crypto;
  if (!c?.subtle?.deriveKey || !c.getRandomValues) return "missing";
  return null;
}

export function vaultCryptoAvailable(): boolean {
  return vaultCryptoBlockReason() === null;
}

/** The platform's WebCrypto. Replaced only by tests; production runs in the browser. */
function subtle(): Crypto {
  const reason = vaultCryptoBlockReason();
  if (reason === "insecure") throw new VaultCryptoError(INSECURE_CONTEXT_MSG);
  if (reason === "missing") throw new VaultCryptoError(MISSING_WEBCRYPTO_MSG);
  return (globalThis as CryptoGlobal).crypto!;
}

export class VaultCryptoError extends Error {}

// ---- base64 ---------------------------------------------------------------

export function toBase64(bytes: Uint8Array): string {
  let bin = "";
  for (let i = 0; i < bytes.length; i += 1024) {
    bin += String.fromCharCode(...bytes.subarray(i, Math.min(i + 1024, bytes.length)));
  }
  return btoa(bin);
}

/** Strict: base64 with whitespace inside is a damaged blob, not something to forgive. */
export function fromBase64(text: string): Uint8Array<ArrayBuffer> {
  const clean = (text ?? "").trim();
  if (!/^[A-Za-z0-9+/]*={0,2}$/.test(clean)) throw new VaultCryptoError("cofre: não base64");
  let bin: string;
  try {
    bin = atob(clean);
  } catch {
    throw new VaultCryptoError("cofre: base64 invalido");
  }
  // Over an ArrayBuffer, so the result is a plain `Uint8Array<ArrayBuffer>`: the WebCrypto
  // signatures want a real buffer and a SharedArrayBuffer view would not compile.
  const buffer = new ArrayBuffer(bin.length);
  const out = new Uint8Array(buffer);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

// ---- key derivation ------------------------------------------------------

/** 32 fresh bytes from the CSPRNG. The salt is generated once, ever, and stays public. */
export function randomSalt(): string {
  return toBase64(subtle().getRandomValues(new Uint8Array(SALT_BYTES)));
}

function iterations(value: number): number {
  if (!Number.isInteger(value) || value < MIN_ITERATIONS || value > MAX_ITERATIONS) {
    throw new VaultCryptoError(`cofre: PBKDF2 pede entre ${MIN_ITERATIONS.toLocaleString("pt-BR")} e ${MAX_ITERATIONS.toLocaleString("pt-BR")} interações`);
  }
  return value;
}

/**
 * Refuse a vault the client was not built for. The parameters are stored on the server and
 * read back at unlock; a future writer that starts using argon2 must fail here loudly rather
 * than derive a key that opens nothing.
 */
export function sameKdf(kdf: string): void {
  if (kdf !== KDF) {
    throw new VaultCryptoError(`cofre: this vault uses "${kdf}", and ponto only speaks ${KDF}`);
  }
}

/** The master password in the only form it ever takes: a key, in memory, non-extractable. */
export async function deriveKey(master: string, saltB64: string, iters: number = ITERATIONS): Promise<CryptoKey> {
  if (!master) throw new VaultCryptoError("Escribe the master password.");
  const c = subtle();
  iterations(iters);
  const base = await c.subtle.importKey("raw", encoder.encode(master), "PBKDF2", false, ["deriveKey"]);
  return c.subtle.deriveKey(
    { name: "PBKDF2", salt: fromBase64(saltB64), iterations: iters, hash: "SHA-256" },
    base,
    { name: "AES-GCM", length: 256 },
    // false, on purpose: an extractable key could be copied out of this page.
    false,
    ["encrypt", "decrypt"],
  );
}

// ---- sealing -------------------------------------------------------------

export const aadCheck = () => "ponto:vault:check";
export const aadItem = (companyId: number) => `ponto:vault:item:${companyId}`;
export const aadExport = (count: number) => `ponto:vault:export:${count}`;

function checkLength(blob: string): void {
  if (blob.length > MAX_BLOB_CHARS) {
    throw new VaultCryptoError(`cofre: record too big (${blob.length} chars; the limit is ${MAX_BLOB_CHARS}). Shorten the description.`);
  }
}

/** `base64(iv ‖ ciphertext ‖ tag)` — WebCrypto already appends the tag, like Java. */
export async function seal(key: CryptoKey, plaintext: string, aad: string): Promise<string> {
  const c = subtle();
  mustAad(aad);
  const iv = c.getRandomValues(new Uint8Array(IV_BYTES));
  const sealed = await c.subtle.encrypt({ name: "AES-GCM", iv, additionalData: encoder.encode(aad) }, key, encoder.encode(plaintext ?? ""));
  const bytes = new Uint8Array(iv.length + sealed.byteLength);
  bytes.set(iv, 0);
  bytes.set(new Uint8Array(sealed), iv.length);
  const blob = toBase64(bytes);
  checkLength(blob);
  return blob;
}

/** Any damage, any wrong key, any other company's context: throws. Nothing returns "" silently. */
export async function open(key: CryptoKey, blob: string, aad: string): Promise<string> {
  const all = fromBase64(blob);
  if (all.length < IV_BYTES + TAG_BYTES) throw new VaultCryptoError("cofre: record truncado");
  mustAad(aad);
  try {
    const plain = await subtle().subtle.decrypt(
      { name: "AES-GCM", iv: all.subarray(0, IV_BYTES), additionalData: encoder.encode(aad) },
      key,
      all.subarray(IV_BYTES),
    );
    return decoder.decode(plain);
  } catch {
    throw new VaultCryptoError("cofre: this record did not open — wrong master password, or the blob was altered.");
  }
}

/**
 * AAD must be text. Without this check, a caller that passes the helper instead of its
 * result (`aadItem` rather than `aadItem(3)`) gets "wrong master password" — a lie,
 * and the kind of lie that sends someone looking in the wrong place for an hour.
 */
function mustAad(aad: string): void {
  if (typeof aad !== "string") throw new VaultCryptoError("cofre: the authenticated context must be text, not " + typeof aad);
}

// ---- the handshake -------------------------------------------------------

/** The blob that says "this key is the vault's key": a fixed string, sealed. */
export async function sealCheck(key: CryptoKey): Promise<string> {
  return seal(key, CHECK_TEXT, aadCheck());
}

/** True only if the key opens the verifier. The server cannot run this check. */
export async function openCheck(key: CryptoKey, blob: string): Promise<boolean> {
  if (!blob) return false;
  try {
    return (await open(key, blob, aadCheck())) === CHECK_TEXT;
  } catch {
    return false;
  }
}
