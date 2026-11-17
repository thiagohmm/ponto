package ponto.view;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.SecretKey;

import ponto.Bad;
import ponto.model.VaultCrypto;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The backup file, in Java — the terminal's copy of `web/src/vault/file.ts`.
 *
 * Same format, same rules: `ponto-cofre/1`, one outer sealed blob over the whole list,
 * and `VaultFileTest` proves a file written here opens in the browser's reader and the
 * other way round.
 *
 * The outer seal is what makes a backup a backup. A file of independently sealed records
 * would accept a deleted line, a swapped pair or an appended forgery without a word,
 * because each blob still opens on its own. With one GCM tag over the whole list, the
 * file refuses to open and says so.
 *
 * The header is in the clear on purpose: `kdf`, `salt` and `iterations` are public
 * parameters, not secrets, and carrying them is what lets a backup taken before a re-key
 * still open with the same master password. `count` is the outer AAD, so a header edited
 * to match a trimmed list breaks the tag instead of quietly importing fewer records.
 *
 * A plaintext list is refused before anything is derived — cheaper, and a clearer message
 * than "did not open": a cofre that would import a CSV of passwords is a cofre that
 * keeps passwords somewhere in the clear.
 */
public final class VaultFile {

    public static final String FORMAT = "ponto-cofre/1";
    public static final String APP = "ponto";

    private static final JsonMapper MAPPER = new JsonMapper();

    /** One row of the sealed list: a company tag, a blob, the revision it came from. */
    public record Row(int companyId, String blob, int revision) { }

    /**
     * Seal the whole cofre into the text of a file.
     *
     * Every record arrives already plaintext from the caller's point of view; here it is
     * re-sealed with the caller's key and its own company AAD, and then the list is
     * sealed again. The inner seal keeps the per-company binding inside the file, the
     * outer one makes the file atomic.
     */
    public static String write(SecretKey key, List<Open> items, String salt, int iterations) {
        if (salt == null || salt.isEmpty()) throw new Bad("cofre: no salt to seal the backup with.");
        List<Row> rows = new ArrayList<>();
        for (Open o : items) {
            String plain = ponto.model.VaultPayload.json(o.plain());
            rows.add(new Row(o.plain().companyId(),
                             VaultCrypto.encryptText(key, plain, VaultCrypto.aadItem(o.plain().companyId())),
                             o.revision()));
        }
        String list = listJson(rows);
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("format", FORMAT);
        file.put("app", APP);
        file.put("exportedAt", Instant.now().toString());
        file.put("kdf", VaultCrypto.KDF);
        file.put("salt", salt);
        file.put("iterations", iterations);
        file.put("count", rows.size());
        file.put("sealed", VaultCrypto.encryptText(key, list, VaultCrypto.aadExport(rows.size())));
        return MAPPER.writeValueAsString(file) + "\n";
    }

    /** A record as the caller holds it: plaintext in memory, plus the revision to keep. */
    public record Open(ponto.model.VaultPayload.Plain plain, int revision) { }

    /** The records of a backup, opened. Each one is checked before anything else sees it. */
    public static List<ponto.model.VaultPayload.Plain> read(String text, String passphrase) {
        JsonNode file = shape(text);
        String salt = textOf(file, "salt");
        int iterations = file.get("iterations").asInt(0);
        int count = file.get("count").asInt(-1);
        String sealed = textOf(file, "sealed");
        SecretKey key = VaultCrypto.deriveKey(passphrase, VaultCrypto.decode(salt), iterations);
        String list = VaultCrypto.decryptText(key, sealed, VaultCrypto.aadExport(count));
        JsonNode rows = parse(list, "cofre: the backup's list is not readable.");
        if (!rows.isArray()) throw new Bad("cofre: the backup's list is not a list.");
        if (rows.size() != count) throw new Bad("cofre: the backup says " + count + " records and holds " + rows.size() + ".");
        List<ponto.model.VaultPayload.Plain> out = new ArrayList<>();
        for (JsonNode row : rows) out.add(openRow(key, row));
        return out;
    }

    /** Header checks first, so a wrong file is named instead of blamed on the password. */
    private static JsonNode shape(String text) {
        if (looksLikePlaintextList(text)) throw new Bad("cofre: this file holds passwords in the clear. The cofre only imports sealed backups.");
        JsonNode tree = parse(text, "cofre: this is not a backup file (the JSON does not parse).");
        if (!tree.isObject()) throw new Bad("cofre: this is not a backup file.");
        String format = textOf(tree, "format");
        if (!FORMAT.equals(format))
            throw new Bad("cofre: backup format \"" + (format.isEmpty() ? "(none)" : format) + "\" — this app writes " + FORMAT + ".");
        String kdf = textOf(tree, "kdf");
        if (!VaultCrypto.KDF.equals(kdf))
            throw new Bad("cofre: backup uses \"" + (kdf.isEmpty() ? "(none)" : kdf) + "\" — ponto only speaks " + VaultCrypto.KDF + ".");
        if (textOf(tree, "salt").isEmpty()) throw new Bad("cofre: backup has no salt.");
        if (!tree.has("iterations") || !tree.get("iterations").isNumber()) throw new Bad("cofre: backup has no iteration count.");
        if (!tree.has("count") || !tree.get("count").isNumber()) throw new Bad("cofre: backup has no record count.");
        if (tree.get("count").asInt(-1) < 0) throw new Bad("cofre: backup has a negative record count.");
        if (textOf(tree, "sealed").isEmpty()) throw new Bad("cofre: backup has no sealed list.");
        return tree;
    }

    /**
     * One record: untrusted input, so every field is checked before anything else sees it.
     * The blob is opened in its own company's context, then parsed by the same strict
     * parser the live list uses — a record that will not import is named, not skipped.
     */
    private static ponto.model.VaultPayload.Plain openRow(SecretKey key, JsonNode row) {
        if (!row.isObject()) throw new Bad("cofre: backup record is not an object.");
        String blob = textOf(row, "blob");
        if (blob.isEmpty()) throw new Bad("cofre: backup record has no blob.");
        if (!row.has("companyId") || !row.get("companyId").isNumber()) throw new Bad("cofre: backup record has no company.");
        int companyId = row.get("companyId").asInt(0);
        if (companyId < 1) throw new Bad("cofre: backup record has no company.");
        String text;
        try { text = VaultCrypto.decryptText(key, blob, VaultCrypto.aadItem(companyId)); }
        catch (RuntimeException e) { throw new Bad("cofre: the backup's record for company " + companyId + " did not open."); }
        ponto.model.VaultPayload.Plain p = ponto.model.VaultPayload.parse(text);
        if (p.companyId() != companyId) throw new Bad("cofre: record company " + p.companyId() + " is not its tag " + companyId + ".");
        return new ponto.model.VaultPayload.Plain(p.app(), p.user(), p.pass(), p.desc(), companyId);
    }

    /** Refuse a plaintext list before deriving anything. */
    public static boolean looksLikePlaintextList(String text) {
        JsonNode tree;
        try { tree = MAPPER.readTree(text); }
        catch (RuntimeException e) { return false; }
        if (!tree.isArray()) return false;
        for (JsonNode n : tree) if (n.isObject() && n.has("pass")) return true;
        return false;
    }

    /** The file's name, dated so a folder of backups still sorts usefully. */
    public static String fileName(Instant now) {
        String s = now.toString().substring(0, 19).replace(":", "").replace("-", "-");
        return "ponto-cofre-" + s + ".json";
    }

    private static JsonNode parse(String raw, String failure) {
        try { return MAPPER.readTree(raw); }
        catch (RuntimeException e) { throw new Bad(failure); }
    }

    /**
     * A member read as text, or empty. `get` returns null for a member that is not
     * there, and a null on this path would be a stack trace where a sentence belongs:
     * the file came from somewhere else, and the user deserves to be told so.
     */
    private static String textOf(JsonNode tree, String key) {
        if (!tree.has(key)) return "";
        var n = tree.get(key);
        if (!n.isString() && !n.isNumber()) throw new Bad("cofre: backup's '" + key + "' is not text.");
        return n.asString("");
    }

    private static String listJson(List<Row> rows) {
        List<Map<String, Object>> m = new ArrayList<>();
        for (Row r : rows) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("companyId", r.companyId());
            one.put("blob", r.blob());
            one.put("revision", r.revision());
            m.add(one);
        }
        return MAPPER.writeValueAsString(m);
    }
}
