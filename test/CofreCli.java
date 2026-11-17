package ponto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.SecretKey;

import ponto.infra.Db;
import ponto.app.Service;
import ponto.model.VaultCrypto;
import ponto.model.VaultPayload;
import ponto.model.VaultItem;
import ponto.model.VaultRewrap;
import ponto.view.VaultFile;

/**
 * The cofre, end to end, against the real database. Run after compile:
 * `java -cp ... ponto.CofreCli <jdbc-url>`.
 *
 * Unit tests prove the envelope and the routes. This one proves the promise the user
 * actually makes: that a vault created here, used for two companies, backed up, wiped
 * and re-keyed, leaves nothing readable behind and loses nothing that was not asked
 * to be lost.
 *
 * Three things are checked in the database itself, because that is where a leak would
 * live and where no unit test looks: no plaintext password appears in any column, a
 * re-key replaces every blob (an old ciphertext still in the table would keep a
 * record openable with the abandoned master password), and forgetting one company
 * leaves the other's rows alone.
 *
 * The browser is present twice, as `test/vault-node.mjs`: it opens the file the
 * terminal wrote, and its own writer produces the file the terminal imports. A backup
 * that only Java can read is not a backup.
 */
public final class CofreCli {

    private static final String MASTER = "café-master-123";
    private static final String NEW_MASTER = "novo-master-456";

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : ponto.TestDb.freshUrl();
        try (Db db = Db.open(java.nio.file.Path.of(System.getProperty("java.io.tmpdir") + "/ponto-cofre-cli"), url)) {
            Service s = new Service(db);
            int company = s.activeCompany().id();
            int other = s.createCompany("Another", "", "8").id();

            var meta = s.vaultMeta();
            if (meta.sealed()) die("a fresh schema's cofre is already sealed");
            try { s.createVaultItem(company, sealedNoise(1)); die("an item was stored in a cofre that was never created"); }
            catch (VaultSealed ignored) { }

            // ---- seal -------------------------------------------------------
            String salt = VaultCrypto.encode(VaultCrypto.randomSalt());
            SecretKey key = VaultCrypto.deriveKey(MASTER, VaultCrypto.decode(salt), VaultCrypto.ITERATIONS);
            s.vaultCreate(VaultCrypto.KDF, salt, VaultCrypto.ITERATIONS, VaultCrypto.checkBlob(key));
            if (!s.vaultMeta().sealed()) die("the cofre did not seal");
            if (!VaultCrypto.checkOk(key, s.vaultMeta().check())) die("the cofre will not open with its own password");
            var wrong = VaultCrypto.deriveKey("not it", VaultCrypto.decode(salt), VaultCrypto.ITERATIONS);
            if (VaultCrypto.checkOk(wrong, s.vaultMeta().check())) die("a wrong password opened the cofre");

            // ---- two companies, one key -------------------------------------
            List<String> secrets = List.of("alpha-s3cr3t-one", "bravo-s3cr3t-two", "charlie-s3cr3t-three");
            add(s, key, company, "Gmail", "ana", secrets.get(0));
            add(s, key, other, "VPN", "ana", secrets.get(1));
            add(s, key, company, "Jira", "ana.santae", secrets.get(2));
            if (s.vaultCount() != 3) die("count " + s.vaultCount());
            if (s.vaultItems(other).size() != 1) die("company filter");
            for (VaultItem it : s.vaultItems(0)) {
                VaultPayload.Plain p = open(key, it);
                if (p.pass().isEmpty()) die("an empty secret came back");
            }

            // ---- a file written here, opened by the browser ---------------
            Path file = Path.of(System.getProperty("java.io.tmpdir") + "/ponto-cofre-" + System.currentTimeMillis() + ".json");
            List<VaultFile.Open> all = new ArrayList<>();
            for (VaultItem it : s.vaultItems(0)) all.add(new VaultFile.Open(open(key, it), it.revision()));
            Files.writeString(file, VaultFile.write(key, all, salt, VaultCrypto.ITERATIONS), java.nio.charset.StandardCharsets.UTF_8);
            String byBrowser = runNode("filerd", MASTER, Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
            for (String want : secrets)
                if (!byBrowser.contains(want)) die("the browser's reader lost " + want);
            String fromBrowser = runNode("filewr", VaultCrypto.encode(key.getEncoded()), salt, String.valueOf(VaultCrypto.ITERATIONS),
                    "[{\"companyId\":" + company + ",\"app\":\"Git\",\"user\":\"ana\",\"pass\":\"delta-s3cr3t\",\"desc\":\"\",\"revision\":1}]");
            List<VaultPayload.Plain> imported = VaultFile.read(fromBrowser, MASTER);
            if (imported.size() != 1 || !imported.getFirst().pass().equals("delta-s3cr3t")) die("the terminal could not read the browser's file");

            // ---- nothing readable in the database -------------------------
            List<String> plain = plaintextInDb(db, secrets);
            if (!plain.isEmpty()) die("plaintext in the database: " + plain);
            try { s.createVaultItem(company, "dG90aXlz"); die("text that is not sealed was accepted"); }
            catch (Bad ignored) { }

            // ---- forget one company, keep the other -----------------------
            int kept = s.vaultCount(company);
            if (s.deleteVaultCompany(other) != 1) die("wipe count");
            if (s.vaultCount(other) != 0 || s.vaultCount(company) != kept) die("the wipe took more than it was asked to");

            // ---- re-key: every blob must move -----------------------------
            List<String> before = new ArrayList<>();
            for (VaultItem it : s.vaultItems(0)) before.add(it.blob());
            String newSalt = VaultCrypto.encode(VaultCrypto.randomSalt());
            SecretKey newer = VaultCrypto.deriveKey(NEW_MASTER, VaultCrypto.decode(newSalt), 120_000);
            List<VaultRewrap> items = new ArrayList<>();
            for (VaultItem it : s.vaultItems(0)) {
                VaultPayload.Plain p = open(key, it);
                items.add(new VaultRewrap(it.id(),
                        VaultCrypto.encryptText(newer, VaultPayload.json(p), VaultCrypto.aadItem(it.companyId())),
                        it.revision()));
            }
            var after = s.vaultRekey(VaultCrypto.KDF, newSalt, 120_000, VaultCrypto.checkBlob(newer), items);
            if (VaultCrypto.checkOk(key, after.check())) die("the abandoned master password still opens the cofre");
            if (!VaultCrypto.checkOk(newer, after.check())) die("the new master password does not open the cofre");
            if (s.vaultItems(0).size() != items.size()) die("the re-key lost a record");
            for (VaultItem it : s.vaultItems(0)) {
                if (before.contains(it.blob())) die("an old blob survived the re-key at #" + it.id());
                // the abandoned master password must not open a single record — if it
                // still did, the old key would be a second door into the same vault
                try { open(key, it); die("the OLD password still opens #" + it.id()); }
                catch (RuntimeException ignored) { }
                open(newer, it);
            }

            // ---- what a backup is for ---------------------------------------
            try { VaultFile.read(fromBrowser, "some other password"); die("a wrong passphrase opened a backup"); }
            catch (RuntimeException e) { if (!e.getMessage().contains("did not open") && !e.getMessage().contains("corrupto")) die("wrong message: " + e.getMessage()); }
            try { VaultFile.read("[{\"app\":\"x\",\"pass\":\"oops\"}]", MASTER); die("a plaintext list imported"); }
            catch (Bad e) { if (!e.getMessage().contains("in the clear")) die("wrong message: " + e.getMessage()); }

            // ---- the key is the only door ------------------------------------
            if (s.vaultMeta().iterations() < VaultCrypto.MIN_ITERATIONS) die("iterations");
            if (Files.exists(file)) { Files.deleteIfExists(file); }
            System.out.println("PASS  cofre   seal + two companies + backup both ways + re-key + wipe, nothing in the clear");
        }
    }

    private static void add(Service s, SecretKey key, int company, String app, String user, String pass) {
        VaultPayload.Plain p = new VaultPayload.Plain(app, user, pass, "", company);
        s.createVaultItem(company, VaultCrypto.encryptText(key, VaultPayload.json(p), VaultCrypto.aadItem(company)));
    }

    private static VaultPayload.Plain open(SecretKey key, VaultItem it) {
        return VaultPayload.parse(VaultCrypto.decryptText(key, it.blob(), VaultCrypto.aadItem(it.companyId())));
    }

    /** A sealed blob with nothing useful inside, for the "no items before a cofre" rule. */
    private static String sealedNoise(int company) {
        SecretKey k = VaultCrypto.deriveKey("x", VaultCrypto.decode(VaultCrypto.encode(new byte[32].clone())), VaultCrypto.MIN_ITERATIONS);
        return VaultCrypto.encryptText(k, "{\"app\":\"x\",\"user\":\"\",\"pass\":\"y\",\"desc\":\"\",\"company\":" + company + "}", VaultCrypto.aadItem(company));
    }

    /** Every plaintext secret, looked for in every text column of the vault's tables. */
    private static List<String> plaintextInDb(Db db, List<String> secrets) {
        List<String> found = new ArrayList<>();
        List<String[]> cols = new ArrayList<>();
        try (var q = db.conn().prepareStatement(
                "select table_name, column_name from information_schema.columns "
                + "where table_schema = current_schema() and data_type in ('text','character varying')")) {
            try (var rs = q.executeQuery()) { while (rs.next()) cols.add(new String[] { rs.getString(1), rs.getString(2) }); }
        } catch (SQLException e) { die("columns: " + e.getMessage()); }
        for (String[] tc : cols) {
            if (!tc[0].startsWith("vault")) continue;
            for (String secret : secrets) {
                try (var one = db.conn().prepareStatement(
                        "select count(*) from \"" + tc[0] + "\" where \"" + tc[1] + "\" like ?")) {
                    one.setString(1, "%" + secret + "%");
                    try (var rs = one.executeQuery()) {
                        rs.next();
                        if (rs.getInt(1) > 0) found.add(tc[0] + "." + tc[1]);
                    }
                } catch (SQLException e) { die("query on " + tc[0] + ": " + e.getMessage()); }
            }
        }
        return found;
    }

    private static String runNode(String... args) throws Exception {
        String[] cmd = new String[args.length + 2];
        cmd[0] = "node";
        cmd[1] = "test/vault-node.mjs";
        for (int i = 0; i < args.length; i++) cmd[i + 2] = args[i];
        var p = new ProcessBuilder(List.of(cmd)).redirectErrorStream(true).start();
        String text = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        int code = p.waitFor();
        if (code != 0) throw new RuntimeException("node (" + args[0] + "): " + text);
        return text;
    }

    private static void die(String m) {
        System.out.println("FAIL  " + m);
        System.exit(1);
    }
}
