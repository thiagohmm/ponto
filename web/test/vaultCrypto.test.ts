import { afterAll, beforeAll, expect, it, vi } from "vitest";
import { webcrypto } from "node:crypto";
import { execFileSync } from "node:child_process";
import {
  aadCheck, aadItem, aadExport, deriveKey, fromBase64, open, openCheck, randomSalt, seal, sealCheck,
  CHECK_TEXT, ITERATIONS, KDF, MAX_ITERATIONS, MIN_ITERATIONS, SALT_BYTES, toBase64,
  vaultCryptoAvailable, vaultCryptoBlockReason,
} from "../src/vault/crypto";

/**
 * The browser half of the envelope.
 *
 * jsdom ships a `crypto.subtle` that accepts calls and then fails in ways a real browser
 * never does, so the global is replaced with Node's own WebCrypto. What is under test is
 * the *format*, not the platform: the same PBKDF2 parameters, the same nonce placement,
 * the same AAD strings as `VaultCrypto.java`.
 *
 * Two kinds of proof, deliberately different:
 *
 * - A fixed vector (`KEY_B64`) pinned in `VaultCryptoTest.java`: the same master password,
 *   salt and iteration count must reach the same 32 key bytes in both languages. If the
 *   parameters drift, the vector is where it shows.
 * - Live round trips through `test/vault-node.mjs`, the third implementation (OpenSSL via
 *   Node's non-subtle API). What this file seals, it opens there; what it seals, this file
 *   opens. A format that only one implementation can read is a vault nobody can unlock.
 *
 * The wrong-password and tamper tests matter more here than anywhere else: the server
 * cannot tell a bad key from a good one, so the *client* is the only place a mistake
 * surfaces. If these ever pass by returning "" instead of throwing, the vault has already
 * lost the property it exists for.
 */

const MASTER = "café-π 123";
const SALT = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
const ITERS = 100_000;
/** Pinned in VaultCryptoTest.java. Same inputs, same 32 bytes, in both languages. */
const KEY_B64 = "ymbU6Vwmj6pk+tnohpK36g5LAcqE9Ns9cJioIM6I27Q=";

import { existsSync } from "node:fs";
import { resolve } from "node:path";

/** vitest runs with cwd = web/; the mirror lives there, and this says so if it moves. */
const HELPER = resolve(process.cwd(), "..", "test", "vault-node.mjs");
if (!existsSync(HELPER)) throw new Error("missing mirror: " + HELPER);

/** The mirror: Node's OpenSSL-backed API, which is the shape Java's JCE takes. */
function mirror(op: string, ...args: string[]): string {
  return execFileSync(process.execPath, [HELPER, op, ...args], { encoding: "utf8" }).trim();
}

beforeAll(() => vi.stubGlobal("crypto", webcrypto));
afterAll(() => vi.unstubAllGlobals());

async function key(master: string, saltB64 = SALT, iters = ITERS, extractable = false): Promise<CryptoKey> {
  const base = await crypto.subtle.importKey("raw", new TextEncoder().encode(master), "PBKDF2", false, ["deriveKey"]);
  return crypto.subtle.deriveKey(
    { name: "PBKDF2", salt: fromBase64(saltB64), iterations: iters, hash: "SHA-256" },
    base, { name: "AES-GCM", length: 256 }, extractable, ["encrypt", "decrypt"],
  );
}

it("derives the vector key both languages agree on", async () => {
  const raw = await crypto.subtle.exportKey("raw", await key(MASTER, SALT, ITERS, true));
  expect(toBase64(new Uint8Array(raw))).toBe(KEY_B64);
  // …and the mirror agrees, byte for byte, with no help from WebCrypto.
  expect(mirror("key", MASTER, SALT, String(ITERS))).toBe(KEY_B64);
});

it("seals what the mirror opens, and opens what the mirror sealed", async () => {
  const k = await deriveKey(MASTER, SALT, ITERS);
  const plain = '{"app":"Gmail","user":"ana","pass":"s3cr3t · ✓","desc":"","company":1}';

  const here = await seal(k, plain, aadItem(1));
  expect(mirror("dec", KEY_B64, aadItem(1), here)).toBe(plain);

  const there = mirror("enc", KEY_B64, aadItem(1), plain);
  expect(await open(k, there, aadItem(1))).toBe(plain);

  // Same plaintext, twice: a fresh nonce every time, or the blobs would be identical.
  expect(await seal(k, plain, aadItem(1))).not.toBe(here);
});

it("the verifier blob is the only thing that says the key is right", async () => {
  const right = await deriveKey(MASTER, SALT, ITERS);
  const wrong = await deriveKey("cafe-pi 123", SALT, ITERS);
  const check = await sealCheck(right);
  expect(await open(right, check, aadCheck())).toBe(CHECK_TEXT);
  expect(await openCheck(right, check)).toBe(true);
  expect(await openCheck(wrong, check)).toBe(false);
  await expect(open(wrong, check, aadCheck())).rejects.toThrow("did not open");
});

it("one flipped character is a refusal, not a quiet maybe", async () => {
  const k = await deriveKey(MASTER, SALT, ITERS);
  const blob = await seal(k, "plaintext that is long enough to matter", aadItem(1));
  const bytes = fromBase64(blob);
  bytes[bytes.length - 1] ^= 0x01; // auth tag
  await expect(open(k, toBase64(bytes), aadItem(1))).rejects.toThrow("did not open");

  const body = fromBase64(blob);
  body[20] ^= 0x40; // ciphertext
  await expect(open(k, toBase64(body), aadItem(1))).rejects.toThrow("did not open");

  const nonce = fromBase64(blob);
  nonce[0] ^= 0x01; // nonce
  await expect(open(k, toBase64(nonce), aadItem(1))).rejects.toThrow("did not open");
});

it("a record sealed for one company will not open in another's context", async () => {
  const k = await deriveKey(MASTER, SALT, ITERS);
  const blob = await seal(k, "bound to company 1", aadItem(1));
  await expect(open(k, blob, aadItem(2))).rejects.toThrow("did not open");
  expect(await open(k, blob, aadItem(1))).toBe("bound to company 1");
  expect(aadItem(7)).toBe("ponto:vault:item:7");
  expect(aadExport(3)).toBe("ponto:vault:export:3");
});

it("names a mistaken context instead of blaming the master password", async () => {
  const k = await deriveKey(MASTER, SALT, ITERS);
  // Passing the helper instead of its result is a typo, not a bad key. Saying so is
  // the difference between a five-second fix and an hour of looking in the wrong place.
  await expect(seal(k, "x", aadItem as unknown as string)).rejects.toThrow("authenticated context must be text");
  await expect(open(k, await seal(k, "x", aadItem(1)), undefined as unknown as string)).rejects.toThrow("authenticated context must be text");
});

it("refuses a weak iteration count instead of trusting the caller", async () => {
  await expect(deriveKey(MASTER, SALT, MIN_ITERATIONS - 1)).rejects.toThrow("interações");
  await expect(deriveKey(MASTER, SALT, MAX_ITERATIONS + 1)).rejects.toThrow("interações");
  await expect(deriveKey("", SALT, ITERS)).rejects.toThrow("master password");
});

it("the salt is the CSPRNG's, 32 bytes, never a repeat", async () => {
  const a = randomSalt();
  expect(fromBase64(a)).toHaveLength(SALT_BYTES);
  expect(randomSalt()).not.toBe(a);
  // Whitespace inside a blob is damage, not something to forgive into a "valid" key.
  expect(() => fromBase64("AAA\nECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")).toThrow("não base64");
});

it("a record over the server's ceiling is refused before the round trip", async () => {
  const k = await deriveKey(MASTER, SALT, ITERS);
  await expect(seal(k, "x".repeat(90_000), aadItem(1))).rejects.toThrow("record too big");
});

it("names the missing platform instead of sealing in the dark", () => {
  vi.stubGlobal("crypto", {} as unknown as Crypto);
  expect(() => randomSalt()).toThrow("WebCrypto");
  expect(vaultCryptoBlockReason()).toBe("missing");
  expect(vaultCryptoAvailable()).toBe(false);
  vi.stubGlobal("crypto", webcrypto);
});

it("blames the insecure page, not the browser, when subtle is withheld", () => {
  vi.stubGlobal("isSecureContext", false);
  expect(vaultCryptoBlockReason()).toBe("insecure");
  expect(vaultCryptoAvailable()).toBe(false);
  expect(() => randomSalt()).toThrow(/HTTPS/);
  vi.stubGlobal("isSecureContext", true);
  expect(vaultCryptoBlockReason()).toBe(null);
  expect(vaultCryptoAvailable()).toBe(true);
});

it("the constants the server checks against are the ones in Java", () => {
  expect([KDF, ITERATIONS, MIN_ITERATIONS, MAX_ITERATIONS, SALT_BYTES]).toEqual([
    "PBKDF2-SHA256", 310_000, 100_000, 5_000_000, 32,
  ]);
});
