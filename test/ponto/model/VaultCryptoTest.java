package ponto.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import ponto.Bad;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The vault's envelope, and the two properties the feature is made of.
 *
 * First: the key must be the <em>same</em> key the browser derives. A master password
 * is hashed in JavaScript (WebCrypto) here and in Java (the CLI menu) — two
 * implementations of one promise. {@link #pbkdf2MatchesTheBrowser} pins that with a
 * vector taken from Node's {@code crypto.pbkdf2Sync}, so a future "optimisation" of
 * the loop, the charset or the block order fails loudly instead of quietly locking
 * every user out of their own vault.
 *
 * Second: a sealed blob must not open for anything but its own key and its own
 * context. That is what "zero knowledge" and "one vault, tagged by company" mean:
 * the server holds ciphertext it cannot read, and a record sealed for company 7
 * cannot be replayed as company 8's because the company id is bound as additional
 * authenticated data.
 */
@EnabledIf("nodeAvailable")
final class VaultCryptoTest {

    /** 32 bytes 0,1,2…31 — the fixed salt the vectors below were made with. */
    private static final String SALT = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String MASTER = "café-π 123";
    private static final int ITERS = 100_000;
    /** PBKDF2-SHA256(MASTER, SALT, 100000, 32) as base64 — produced by Node. */
    private static final String KEY_B64 = "ymbU6Vwmj6pk+tnohpK36g5LAcqE9Ns9cJioIM6I27Q=";

    private static final javax.crypto.SecretKey KEY =
            new javax.crypto.spec.SecretKeySpec(VaultCrypto.decode(KEY_B64), "AES");

    private static javax.crypto.SecretKey keyOf(String master) {
        return VaultCrypto.deriveKey(master, VaultCrypto.decode(SALT), ITERS);
    }

    /** The plaintext the browser seals: a record plus the company tag inside it. */
    private static String plain(String app, String user, String pass, String desc, int company) {
        return VaultPayload.json(new VaultPayload.Plain(app, user, pass, desc, company));
    }

    static boolean nodeAvailable() {
        try {
            var p = new ProcessBuilder("node", "--version").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void pbkdf2MatchesTheBrowser() {
        // The whole design rests on this equality: Java's hand-rolled PBKDF2 and
        // WebCrypto's, over the same UTF-8 password, byte for byte.
        assertEquals(KEY_B64, VaultCrypto.encode(keyOf(MASTER).getEncoded()),
                "master password \"" + MASTER + "\" must derive the browser's key");
    }

    @Test
    void spacesAndUnicodeAreNotNormalised() {
        // Trimming or NFC-normalising the master password here would split the vault
        // in two: what the browser sealed, the terminal could never reopen.
        var k = VaultCrypto.deriveKey("  p@ss w/ óñi ✓  ", VaultCrypto.decode(SALT), ITERS);
        String blob = VaultCrypto.encryptText(k, "app: banco", VaultCrypto.aadItem(7));
        assertEquals("app: banco", VaultCrypto.decryptText(k, blob, VaultCrypto.aadItem(7)));
    }

    @Test
    void roundTripsUnicodePlaintext() {
        String text = plain("Gmail", "ana.torres", "p@ss ünïcode ✓ 42+/=", "secundo", 7);
        String blob = VaultCrypto.encryptText(KEY, text, VaultCrypto.aadItem(7));
        assertEquals(text, VaultCrypto.decryptText(KEY, blob, VaultCrypto.aadItem(7)));
        var p = VaultPayload.parse(VaultCrypto.decryptText(KEY, blob, VaultCrypto.aadItem(7)));
        assertEquals("Gmail", p.app());
        assertEquals("p@ss ünïcode ✓ 42+/=", p.pass());
        assertEquals(7, p.companyId(), "the tag inside must be the tag outside");
    }

    @Test
    void sealedBlobCarriesNoPlaintext() {
        // Zero knowledge, tested on the bytes the database will hold: no part of the
        // plaintext — not even the username the UI would filter on — appears inside.
        String text = plain("Globo Pay", "ana.torres", "s3cr3t-top-secret", "desc", 7);
        String blob = VaultCrypto.encryptText(KEY, text, VaultCrypto.aadItem(7));
        String sealed = new String(VaultCrypto.decode(blob), java.nio.charset.StandardCharsets.ISO_8859_1);
        String[] parts = { "Globo Pay", "ana.torres", "s3cr3t-top-secret", "desc" };
        for (var part : parts)
            assertFalse(sealed.contains(part), "leaked \"" + part + "\" into ciphertext");
    }

    @Test
    void eachSealingUsesAFreshNonce() {
        // A fixed nonce would make two identical passwords identical on the wire and
        // in the dump, and would break GCM outright on the second use.
        var first = VaultCrypto.encryptText(KEY, "same text", VaultCrypto.aadItem(7));
        var second = VaultCrypto.encryptText(KEY, "same text", VaultCrypto.aadItem(7));
        assertNotEquals(first, second, "the same plaintext sealed twice");
        assertNotEquals(first.substring(0, 16), second.substring(0, 16), "nonce reused");
    }

    @Test
    void wrongKeyCannotOpen() {
        var k = keyOf("outra senha mestra");
        String blob = VaultCrypto.encryptText(KEY, "secret", VaultCrypto.aadItem(7));
        assertThrows(Bad.class, () -> VaultCrypto.decryptText(k, blob, VaultCrypto.aadItem(7)),
                "a second master password would open the vault");
    }

    @Test
    void oneFlippedByteIsRefused() {
        // GCM's auth tag is the reason to use GCM: damage must be detected, and
        // detection has to fail closed instead of returning half-open text.
        String blob = VaultCrypto.encryptText(KEY, "secret payload", VaultCrypto.aadItem(7));
        byte[] all = VaultCrypto.decode(blob);
        all[20] ^= 1;
        assertThrows(Bad.class, () -> VaultCrypto.decryptText(KEY, VaultCrypto.encode(all),
                VaultCrypto.aadItem(7)), "tampered ciphertext opened");
    }

    @Test
    void truncatedBlobIsRefused() {
        String blob = VaultCrypto.encryptText(KEY, "secret payload", VaultCrypto.aadItem(7));
        String shortBlob = blob.substring(0, blob.length() - 4);
        assertThrows(Bad.class, () -> VaultCrypto.decryptText(KEY, shortBlob, VaultCrypto.aadItem(7)));
        assertThrows(Bad.class, () -> VaultCrypto.decryptText(KEY, "not base64 !", VaultCrypto.aadItem(7)));
    }

    @Test
    void companyIsBoundInTheCiphertext() {
        // The multi-tenant guarantee, at the layer that matters. {@code company_id} is
        // plaintext on the row so the server can count and filter it — the AAD is what
        // stops a relabelled row from being read as another company's credential.
        String for7 = VaultCrypto.encryptText(KEY, "netflix · ana", VaultCrypto.aadItem(7));
        assertEquals("netflix · ana", VaultCrypto.decryptText(KEY, for7, VaultCrypto.aadItem(7)));
        assertThrows(Bad.class, () -> VaultCrypto.decryptText(KEY, for7, VaultCrypto.aadItem(8)),
                "a record sealed for company 7 opened as company 8");
    }

    @Test
    void verifierBlobProvesTheMasterPassword() {
        String check = VaultCrypto.checkBlob(keyOf(MASTER));
        assertTrue(VaultCrypto.checkOk(keyOf(MASTER), check), "right master rejected");
        assertFalse(VaultCrypto.checkOk(keyOf("quase-right"), check), "wrong master accepted");
        assertFalse(VaultCrypto.checkOk(keyOf(MASTER),
                VaultCrypto.encryptText(KEY, VaultCrypto.CHECK_TEXT, VaultCrypto.AAD_ITEM + 1)),
                "verifier opened under an item's context");
    }

    @Test
    void iterationFloorIsAWallNotAHint() {
        // A server that accepted 10 iterations would let an attacker who stole the
        // database test billions of guesses a second. The floor is policy, so it
        // belongs in the server too — see Migrations' CHECK on vault_meta.
        assertThrows(Bad.class, () -> VaultCrypto.deriveKey(MASTER, VaultCrypto.decode(SALT), ITERS - 1),
                "iteration floor bypassed");
        assertThrows(Bad.class, () -> VaultCrypto.deriveKey(MASTER, VaultCrypto.decode(SALT), 10),
                "iteration floor bypassed");
        assertThrows(Bad.class, () -> VaultCrypto.deriveKey(MASTER, new byte[] { 1, 2, 3 }, ITERS),
                "a 3-byte salt was accepted");
        assertEquals(KEY_B64, VaultCrypto.encode(keyOf(MASTER).getEncoded()));
    }

    @Test
    void sealedPayloadFitsTheColumn() {
        // The largest legal record must still fit the column it is stored in — otherwise
        // a legitimate save would fail at the database instead of at the form.
        String text = plain("a".repeat(200), "u".repeat(200), "p".repeat(512), "d".repeat(4000), 1);
        int chars = VaultCrypto.encryptText(KEY, text, VaultCrypto.aadItem(1)).length();
        assertTrue(chars < VaultCrypto.MAX_BLOB_CHARS, "sealed record is " + chars + " chars");
    }

    @Test
    void payloadRefusesAForeignField() {
        // An imported file or a paste is untrusted input: a key we never designed is
        // not silently accepted, because it would be a field the server could log.
        assertThrows(Bad.class, () -> VaultPayload.parse("{\"app\":\"x\",\"hint\":\"top secret\"}"),
                "unknown field accepted");
        assertThrows(Bad.class, () -> VaultPayload.parse("{\"app\":\"x\",\"company\":0}"));
        assertThrows(Bad.class, () -> VaultPayload.parse("{\"app\":\"x\",\"pass\":123}"));
        assertThrows(Bad.class, () -> VaultPayload.parse("not json"));
        var ok = VaultPayload.parse(plain("Gmail", "ana", "p1", "d", 2));
        assertEquals("Gmail · ana", ok.label());
    }

    @Test
    void javaOpensWhatTheBrowserSealed() {
        String blob = runNode("enc", KEY_B64, VaultCrypto.aadItem(7), "hello-from-browser");
        assertEquals("hello-from-browser", VaultCrypto.decryptText(KEY, blob, VaultCrypto.aadItem(7)));
    }

    @Test
    void browserOpensWhatJavaSealed() {
        String blob = VaultCrypto.encryptText(KEY, "hello-from-java", VaultCrypto.aadItem(3));
        assertEquals("hello-from-java", runNode("dec", KEY_B64, VaultCrypto.aadItem(3), blob));
    }

    @Test
    void bothSidesDeriveTheSameKey() {
        assertEquals(KEY_B64, runNode("key", MASTER, SALT, String.valueOf(ITERS)));
    }

    /** Runs the Node mirror of the envelope ({@code test/vault-node.mjs}). */
    private static String runNode(String op, String a, String b, String c) {
        try {
            var proc = new ProcessBuilder("node", "test/vault-node.mjs", op, a, b, c)
                    .redirectErrorStream(true).start();
            String text = new String(proc.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            int code = proc.waitFor();
            assertEquals(0, code, "node vault helper (" + op + ") failed: " + text);
            return text;
        } catch (Exception e) {
            throw new AssertionError("node vault helper (" + op + ") unusável: " + e, e);
        }
    }
}
