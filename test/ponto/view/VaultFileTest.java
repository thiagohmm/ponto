package ponto.view;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;

import ponto.model.VaultCrypto;
import ponto.model.VaultGen;
import ponto.model.VaultPayload;

/**
 * The backup file, proved against the browser.
 *
 * A backup is the one thing a vault user will trust years later, and it is written by
 * two implementations that never talk to each other: this one in the terminal, and
 * `web/src/vault/file.ts` in the page. Anything short of a round trip between them
 * leaves the promise untested — a file the terminal writes that the page cannot read is
 * a backup that fails silently, at the worst possible moment, in an emergency.
 *
 * `test/vault-node.mjs` stands for the page, the same way {@code VaultCryptoTest} uses
 * it: Node's WebCrypto-equivalent code produces the bytes a browser produces.
 *
 * The refusals are the rest of the contract, and each is a different failure of nerve:
 * a plaintext list (a cofre that imports one keeps passwords in the clear), a forged
 * count (a header that lies about how many records it holds), a trimmed file (a deleted
 * line, which is exactly what the outer seal exists to catch), and a record that
 * disagrees with its own company tag.
 */
final class VaultFileTest {

    private static final String MASTER = "café-π 123";
    private static final String SALT = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final int ITERS = 100_000;

    private static SecretKey key() { return VaultCrypto.deriveKey(MASTER, VaultCrypto.decode(SALT), ITERS); }

    /** One credential as the caller holds it in memory: plaintext, plus its company. */
    private static VaultFile.Open row(int company, String app, String pass) {
        return new VaultFile.Open(new VaultPayload.Plain(app, "user" + company, pass, "note " + app, company), 7);
    }

    // ---- the round trip, both ways ------------------------------------------

    @Test
    void aFileWrittenHereOpensInThePagesReader() {
        List<VaultFile.Open> items = List.of(
                row(1, "Gmail", "alpha-s3cr3t-one"),
                row(2, "VPN", "bravo-s3cr3t-two"),
                row(1, "Jira", "charlie-s3cr3t-three"));
        String file = VaultFile.write(key(), items, SALT, ITERS);

        String read = runNode("filerd", MASTER, file);
        List<String> got = rows(read);
        assertEquals(3, got.size(), "the page lost a record: " + read);

        // the same size is not enough: the set of secrets has to be the same set
        List<String> want = items.stream().map(o -> o.plain().pass()).sorted().toList();
        List<String> found = got.stream().map(g -> pass(g)).sorted().toList();
        assertEquals(want, found, "the page opened different passwords: " + read);
        for (String g : got) assertTrue(g.contains("\"companyId\""), "no company tag: " + g);
    }

    @Test
    void aFileThePageWroteOpensHere() {
        String file = runNode("filewr", keyB64(), SALT, String.valueOf(ITERS),
                "[{\"companyId\":4,\"app\":\"Git\",\"user\":\"me\",\"pass\":\"delta-s3cr3t\",\"desc\":\"d\",\"revision\":2}]");
        List<VaultPayload.Plain> plains = VaultFile.read(file, MASTER);
        assertEquals(1, plains.size());
        assertEquals("delta-s3cr3t", plains.getFirst().pass());
        assertEquals(4, plains.getFirst().companyId());
        assertEquals("Git", plains.getFirst().app());
    }

    @Test
    void anEmptyVaultStillWritesAFileThePageReads() {
        String file = VaultFile.write(key(), List.of(), SALT, ITERS);
        assertTrue(file.contains("\"count\":0") || file.contains("\"count\": 0"), file);
        assertEquals("[]", runNode("filerd", MASTER, file).trim());
    }

    // ---- the refusals ------------------------------------------------------

    @Test
    void aPlaintextListIsRefusedBeforeAnythingIsDerived() {
        String plainList = "[{\"app\":\"Gmail\",\"user\":\"me\",\"pass\":\"hunter2\"}]";
        String message = messageOf(() -> VaultFile.read(plainList, MASTER));
        assertTrue(message.contains("in the clear"), "it blamed the password instead: " + message);
    }

    @Test
    void aForgedCountBreaksTheSeal() {
        String file = VaultFile.write(key(), List.of(row(1, "Gmail", "echo-s3cr3t")), SALT, ITERS);
        // the honest-looking way to lose a record is to delete one and fix the header to
        // match; that edit is exactly what the outer seal exists to catch
        String forged = file.replace("\"count\":1", "\"count\":2").replace("\"count\": 1", "\"count\": 2");
        assertNotEquals(file, forged, "the header does not say count: " + file);
        String message = messageOf(() -> VaultFile.read(forged, MASTER));
        assertTrue(message.contains("corrupto") || message.contains("count"), message);
    }

    @Test
    void aFileMissingTheSealRefuses() {
        // the records and the header are there, the thing that made the file a backup is
        // not: importing it would mean trusting a header over content nobody can check
        String file = VaultFile.write(key(), List.of(row(1, "A", "one-s3cr3t")), SALT, ITERS);
        int at = file.indexOf("\"sealed\"");
        assertTrue(at > 0, "no sealed field: " + file);
        // it is the last field, so the comma before it goes too — a JSON object that
        // lost its last member still parses, which is why the refusal must be real
        String noSeal = file.substring(0, file.lastIndexOf(',', at)) + "}";
        assertTrue(!noSeal.contains("\"sealed\""), "did not remove the seal: " + noSeal);
        String message = messageOf(() -> VaultFile.read(noSeal, MASTER));
        assertTrue(message.contains("sealed") || message.contains("not readable"), message);
    }

    @Test
    void aBackupFromAnotherAppRefuses() {
        String file = VaultFile.write(key(), List.of(row(1, "A", "one-s3cr3t")), SALT, ITERS)
                .replace("\"format\":\"ponto-cofre/1\"", "\"format\":\"vault-of-other/2\"");
        String message = messageOf(() -> VaultFile.read(file, MASTER));
        assertTrue(message.contains("format"), message);
    }

    @Test
    void theWrongMasterPasswordSaysSoOnce() {
        String file = VaultFile.write(key(), List.of(row(1, "A", "one-s3cr3t")), SALT, ITERS);
        String message = messageOf(() -> VaultFile.read(file, "not the password"));
        assertTrue(message.contains("incorrecta") || message.contains("corrupto"), message);
    }

    @Test
    void aRecordThatDeniesItsCompanyTagIsRefused() {
        // the file is well-formed, the password is right and the seal holds; only the
        // record lies about which company it belongs to, and importing that puts a
        // secret in the wrong place — the tag is checked, not trusted
        String file = runNode("filewr", keyB64(), SALT, String.valueOf(ITERS),
                "[{\"companyId\":1,\"company\":9,\"app\":\"X\",\"user\":\"u\",\"pass\":\"kilo-s3cr3t\",\"desc\":\"\",\"revision\":1}]");
        String message = messageOf(() -> VaultFile.read(file, MASTER));
        assertTrue(message.contains("company 9") || message.contains("corrupto"), message);
    }

    @Test
    void theFileNameSaysWhatItIsAndCarriesNothingElse() {
        String name = VaultFile.fileName(java.time.Instant.ofEpochSecond(1_700_000_000L));
        assertTrue(name.startsWith("ponto-cofre-"), name);
        assertTrue(name.endsWith(".json"), name);
        assertFalse(name.contains("/") || name.contains(":"), name);
    }

    // ---- the generator, in the same breath ----------------------------------

    @Test
    void theGeneratorKeepsTheRulesItWasGiven() {
        String upper = VaultGen.generate(24, true, false, false, false, false);
        assertEquals(24, upper.length());
        assertTrue(upper.chars().allMatch(Character::isUpperCase), upper);

        String digits = VaultGen.generate(8, false, false, true, false, false);
        assertEquals(8, digits.length());
        assertTrue(digits.chars().allMatch(Character::isDigit), digits);

        String message = messageOf(() -> VaultGen.generate(20, false, false, false, false, false));
        assertTrue(message.contains("at least one"), message);
    }

    @Test
    void twoGeneratedPasswordsAreNotTheSame() {
        assertNotEquals(VaultGen.generate(), VaultGen.generate(), "a CSPRNG that repeats is a seeded one");
        assertEquals(VaultGen.DEFAULT_LENGTH, VaultGen.generate().length());
    }

    @Test
    void avoidingLookAlikesReallyRemovesThem() {
        String p = VaultGen.generate(64, true, true, true, false, true);
        for (char c : VaultGen.SIMILAR.toCharArray())
            assertFalse(p.contains(String.valueOf(c)), "asked to drop " + VaultGen.SIMILAR + " and got " + p);
    }

    @Test
    void aLengthOutsideTheRulesIsNamedNotSilentlyClamped() {
        String message = messageOf(() -> VaultGen.generate(7, true, true, true, false, false));
        assertTrue(message.contains("8") && message.contains("128"), message);
    }

    // ---- plumbing ---------------------------------------------------------

    private static String keyB64() { return VaultCrypto.encode(key().getEncoded()); }

    /** Runs the Node mirror of the envelope and file ({@code test/vault-node.mjs}). */
    private static String runNode(String... args) {
        try {
            String[] cmd = new String[args.length + 2];
            cmd[0] = "node";
            cmd[1] = "test/vault-node.mjs";
            for (int i = 0; i < args.length; i++) cmd[i + 2] = args[i];
            var proc = new ProcessBuilder(List.of(cmd)).redirectErrorStream(true).start();
            String text = new String(proc.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            int code = proc.waitFor();
            assertEquals(0, code, "node vault helper (" + args[0] + ") failed: " + text);
            return text;
        } catch (Exception e) {
            throw new AssertionError("node vault helper (" + args[0] + ") unusável: " + e, e);
        }
    }

    /** The objects of the list the page's reader prints, as strings. */
    private static List<String> rows(String json) {
        String body = json.trim();
        assertTrue(body.startsWith("[") && body.endsWith("]"), "not a list: " + body);
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        char[] cs = body.toCharArray();
        for (int i = 1; i < cs.length - 1; i++) {
            if (cs[i] == '{') { if (depth == 0) start = i; depth++; }
            if (cs[i] == '}') { depth--; if (depth == 0) out.add(new String(cs, start, i + 1 - start)); }
        }
        return out;
    }

    private static String pass(String rowJson) {
        int i = rowJson.indexOf("\"pass\":\"");
        assertTrue(i >= 0, "no pass in " + rowJson);
        return rowJson.substring(i + 8, rowJson.indexOf('"', i + 8));
    }

    /** The sentence a user would read, rather than a class name. */
    private static String messageOf(Runnable r) {
        try { r.run(); } catch (Throwable t) { return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(); }
        throw new AssertionError("it accepted what it must refuse");
    }
}
