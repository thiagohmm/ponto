package ponto.model;

import ponto.Bad;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The cryptography of the password vault: pure Java, no database, no HTTP.
 *
 * The master password never reaches the server and is never stored anywhere —
 * PBKDF2-SHA256 stretches it together with a random salt into a 256-bit key
 * and every record is sealed with AES-256-GCM. GCM authenticates: change one
 * bit of the ciphertext, of the nonce or of the extra data and the tag check
 * fails, so a tampered row can never be quietly opened as a valid credential.
 *
 * Envelope (identical to the one web/src/crypto.ts writes and reads):
 *
 *     base64( iv(12) ‖ ciphertext ‖ tag(16) )
 *
 * The nonce is fresh for every single write — reusing a nonce under the same
 * key is the classic GCM break. The extra authenticated data (AAD) binds a
 * blob to its context, so a blob copied to another company's row, or another
 * row's blob moved into this one, does not open.
 *
 * Brute force is offline by design: the server holds salt + iterations + the
 * verifier blob, which is exactly what an attacker holding a stolen dump needs
 * to attack the master password alone — which is also exactly what makes a
 * dumped vault unreadable without it.
 */
public final class VaultCrypto {

    /** The only key derivation function accepted; a blob from another kdf is refused. */
    public static final String KDF = "PBKDF2-SHA256";
    /** PBKDF2 rounds for a new vault (OWASP 2026 floor for PBKDF2-SHA256). */
    public static final int ITERATIONS = 310_000;
    public static final int MIN_ITERATIONS = 100_000;
    public static final int MAX_ITERATIONS = 5_000_000;
    public static final int KEY_BITS = 256;
    public static final int KEY_BYTES = KEY_BITS / 8;
    /** 32 random bytes: no rainbow table, no two vaults sharing a salt. */
    public static final int SALT_BYTES = 32;
    public static final int IV_BYTES = 12;
    public static final int TAG_BYTES = 16;
    /** Upper bound on a sealed record (a credential is short; a 64 KB blob is a bug). */
    public static final int MAX_BLOB_CHARS = 65_536;

    /** Fixed text sealed in the verifier blob: opening it proves the key. */
    public static final String CHECK_TEXT = "ponto-vault-verifier";
    public static final String AAD_CHECK = "ponto:vault:check";
    public static final String AAD_ITEM = "ponto:vault:item:";
    public static final String AAD_EXPORT = "ponto:vault:export:";

    private static final SecureRandom RANDOM = new SecureRandom();

    private VaultCrypto() { }

    /** A fresh salt for a new vault. */
    public static byte[] randomSalt() {
        byte[] b = new byte[SALT_BYTES];
        RANDOM.nextBytes(b);
        return b;
    }

    /**
     * The vault key: PBKDF2-HMAC-SHA256 over the master password and the salt.
     *
     * PBKDF2 is written out by hand on purpose. {@code PBEKeySpec} feeds the JCE
     * a {@code char[]}, and the provider hashes the <em>low 8 bits of each UTF-16
     * code unit</em> — which is not UTF-8, so every accented master password
     * ("café", "niño") would derive a different key in Java than in the browser
     * (WebCrypto hashes the UTF-8 bytes). Same bytes in, same key out, anywhere.
     *
     * The password is used raw: no trimming, no Unicode normalisation. A trailing
     * space is part of the password, and both clients must agree byte for byte.
     */
    public static SecretKey deriveKey(String master, byte[] salt, int iterations) {
        if (master == null || master.isEmpty()) throw new Bad("escrie uma senha mestra");
        if (salt == null || salt.length < SALT_BYTES) throw new Bad("salt inválido");
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS)
            throw new Bad("PBKDF2: interações fora de range");
        byte[] key = pbkdf2(master.getBytes(StandardCharsets.UTF_8), salt, iterations, KEY_BYTES);
        if (key.length != KEY_BYTES) throw new Bad("cofre: key too short");
        return new SecretKeySpec(key, "AES");
    }

    /** RFC 2898 PBKDF2 with HMAC-SHA256, one block at a time (PRF output is 32 bytes). */
    private static byte[] pbkdf2(byte[] password, byte[] salt, int iterations, int length) {
        final String hmac = "HmacSHA256";
        int blocks = Math.ceilDiv(length, 32);
        byte[] out = new byte[blocks * 32];
        // input = salt ‖ INT_32_BE(blockIndex); the last byte is the only one that moves.
        byte[] input = concat(salt, new byte[] { 0, 0, 0, 0 });
        try {
            var mac = javax.crypto.Mac.getInstance(hmac);
            for (int b = 1; b <= blocks; b++) {
                input[input.length - 1] = (byte) b;
                mac.init(new SecretKeySpec(password, hmac));
                byte[] u = mac.doFinal(input);
                byte[] block = new byte[32];
                System.arraycopy(u, 0, block, 0, 32);
                for (int i = 1; i < iterations; i++) {
                    mac.init(new SecretKeySpec(password, hmac));
                    u = mac.doFinal(u);
                    for (int j = 0; j < 32; j++) block[j] ^= u[j];
                }
                System.arraycopy(block, 0, out, (b - 1) * 32, 32);
            }
        } catch (java.security.GeneralSecurityException e) {
            throw new Bad("PBKDF2: " + e.getMessage());
        }
        return out.length == length ? out : java.util.Arrays.copyOf(out, length);
    }

    /** Seal plaintext: fresh 12-byte nonce, AES-256-GCM, AAD bound to the context. */
    public static String encrypt(SecretKey key, byte[] plain, String aad) {
        if (key == null) throw new Bad("no key");
        if (plain == null) plain = new byte[0];
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            var c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, iv));
            // updateAAD authenticates the context without encrypting it. update() would
            // encrypt it — silently, and the tag would never match on the way back.
            c.updateAAD(aadBytes(aad));
            byte[] sealed = c.doFinal(plain);
            // Java appends the auth tag to doFinal's output, so iv ‖ sealed is the whole
            // envelope: the same iv ‖ ciphertext ‖ tag the browser writes.
            return Base64.getEncoder().encodeToString(concat(iv, sealed));
        } catch (GeneralSecurityException e) {
            throw new Bad("criptografia: " + e.getMessage());
        }
    }

    /** Open a sealed blob. Any damage — or the wrong key — throws {@link Bad}. */
    public static byte[] decrypt(SecretKey key, String blob, String aad) {
        if (key == null) throw new Bad("no key");
        byte[] all = decode(blob);
        if (all.length < IV_BYTES + TAG_BYTES) throw new Bad("cofre: blob truncado");
        try {
            var c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, first(all)));
            c.updateAAD(aadBytes(aad));
            return c.doFinal(rest(all));
        } catch (GeneralSecurityException e) {
            throw new Bad("cofre: senha mestra incorrecta ou record corrupto");
        }
    }

    public static String encryptText(SecretKey key, String text, String aad) {
        return encrypt(key, text.getBytes(StandardCharsets.UTF_8), aad);
    }

    public static String decryptText(SecretKey key, String blob, String aad) {
        return new String(decrypt(key, blob, aad), StandardCharsets.UTF_8);
    }

    /** The verifier blob of a vault, sealed with its own key. */
    public static String checkBlob(SecretKey key) {
        return encryptText(key, CHECK_TEXT, AAD_CHECK);
    }

    /** True only when the key really opens this vault's verifier. */
    public static boolean checkOk(SecretKey key, String blob) {
        try { return decryptText(key, blob, AAD_CHECK).equals(CHECK_TEXT); }
        catch (RuntimeException e) { return false; }
    }

    /** The AAD of one item: the blob is bound to the company that owns it. */
    public static String aadItem(int companyId) { return AAD_ITEM + companyId; }

    /** The AAD of an export file, bound to how many records it carries. */
    public static String aadExport(int count) { return AAD_EXPORT + count; }

    /** base64 of a random key, for tests and for the terminal's own vault files. */
    public static String encode(byte[] b) { return Base64.getEncoder().encodeToString(b); }

    public static byte[] decode(String s) {
        if (s == null || s.isEmpty()) throw new Bad("cofre: não base64");
        try { return Base64.getDecoder().decode(s.trim()); }
        catch (IllegalArgumentException e) { throw new Bad("cofre: não base64"); }
    }

    /** Shape check only — the server never opens a blob, so this is all it can say. */
    public static boolean looksSealed(String blob) {
        if (blob == null || blob.isEmpty() || blob.length() > MAX_BLOB_CHARS) return false;
        try { return decode(blob).length >= IV_BYTES + TAG_BYTES; }
        catch (RuntimeException e) { return false; }
    }

    /** A real salt, as base64: between 16 and 64 decoded bytes. */
    public static boolean looksSalt(String salt) {
        if (salt == null) return false;
        try { int n = decode(salt).length; return n >= 16 && n <= 64; }
        catch (RuntimeException e) { return false; }
    }

    private static byte[] aadBytes(String aad) {
        byte[] b = (aad == null ? "" : aad).getBytes(StandardCharsets.UTF_8);
        if (b.length == 0) throw new Bad("cofre: falta AAD");
        return b;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] first(byte[] all) { return java.util.Arrays.copyOfRange(all, 0, IV_BYTES); }

    private static byte[] rest(byte[] all) { return java.util.Arrays.copyOfRange(all, IV_BYTES, all.length); }
}
