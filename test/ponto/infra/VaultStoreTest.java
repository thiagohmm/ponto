package ponto.infra;

import ponto.app.Service;
import ponto.infra.Migrations;
import ponto.model.VaultCrypto;
import ponto.model.VaultDraft;
import ponto.model.VaultItem;
import ponto.model.VaultPayload;
import ponto.model.VaultRewrap;
import ponto.Bad;
import ponto.Conflict;
import ponto.NotFound;
import ponto.Problema;
import ponto.TestDb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the vault's rows prove, on real PostgreSQL.
 *
 * The promise of the feature is that the server stores what it cannot read. That is
 * not a property of this file — the store is deliberately cipher-free and only moves
 * opaque text. What these tests guard is the <em>other</em> half: that the shape of
 * the storage cannot be widened into something that leaks.
 *
 * The headline test is {@link #theDatabaseHoldsOnlyCiphertext}: seal a record whose
 * plaintext is a sentence nobody would ever mistake for base64, then read the row's
 * own text columns back from SQL and assert the plaintext appears nowhere in them.
 * If a future column ever stores a "hint", or someone swaps a plaintext write into
 * the INSERT, that test is where it surfaces.
 *
 * Everything else here is about the consequences of "one master password, items tagged
 * by company": the list spans companies on purpose, the tag cannot be moved, and a
 * company holding credentials cannot be deleted out from under its user.
 */
final class VaultStoreTest {

    @TempDir Path dir;

    /** A 32-byte key, so the tests stay fast — key derivation is pinned elsewhere. */
    private static final String KEY_B64 = "ymbU6Vwmj6pk+tnohpK36g5LAcqE9Ns9cJioIM6I27Q=";
    private static final String SALT = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final int ITERS = 100_000;

    private static javax.crypto.SecretKey key() {
        return new javax.crypto.spec.SecretKeySpec(VaultCrypto.decode(KEY_B64), "AES");
    }

    private Db open() { return TestDb.open(dir); }

    /** Seal a record the way the browser does: plaintext JSON + the company's AAD. */
    private static String sealed(int company, String app, String user, String pass) {
        return VaultCrypto.encryptText(key(),
                VaultPayload.json(new VaultPayload.Plain(app, user, pass, "", company)),
                VaultCrypto.aadItem(company));
    }

    @Test
    void theVaultStartsUnsealed() {
        try (Db db = open()) {
            var s = new Service(db);
            var m = s.vaultMeta();
            assertFalse(m.sealed(), "a fresh database must not look unlocked");
            assertEquals(0, s.vaultCount());
            assertEquals(VaultCrypto.KDF, m.kdf());
            assertTrue(s.vaultItems(null).isEmpty());
        }
    }

    @Test
    void theVaultIsSealedExactlyOnce() {
        try (Db db = open()) {
            var s = new Service(db);
            var m = s.vaultCreate(VaultCrypto.KDF, SALT, ITERS, VaultCrypto.checkBlob(key()));
            assertTrue(m.sealed());
            assertEquals(SALT, m.salt());
            // A second create would overwrite the verifier: every existing record would
            // stop opening, silently, and the user would have no way to know why.
            assertThrows(Conflict.class, () -> s.vaultCreate(VaultCrypto.KDF, SALT, ITERS,
                    VaultCrypto.checkBlob(key())), "the vault was re-sealed by creating it again");
        }
    }

    @Test
    void theDatabaseHoldsOnlyCiphertext() throws Exception {
        try (Db db = open()) {
            var s = new Service(db);
            s.vaultCreate(VaultCrypto.KDF, SALT, ITERS, VaultCrypto.checkBlob(key()));
            String app = "Portal oficcional Globo", user = "ana.torres@ex.com", pass = "Tr0y-P@ss-Word-9";
            int id = s.createVaultItem(s.companyId(), sealed(s.companyId(), app, user, pass)).id();

            // Read the row back as the database sees it, not as the store presents it.
            try (var q = db.conn().prepareStatement(
                    "SELECT blob, company_id, id, revision FROM vault_item WHERE id = ?")) {
                q.setInt(1, id);
                try (var rs = q.executeQuery()) {
                    assertTrue(rs.next(), "the record was not stored");
                    String blob = rs.getString(1);
                    for (var secret : List.of(app, user, pass, "ana", "Portal", "Tr0y"))
                        assertFalse(blob.contains(secret), "plaintext \"" + secret + "\" reached the column");
                    assertTrue(VaultCrypto.looksSealed(blob), "stored value is not a sealed envelope");
                    // The company tag is the only readable fact about the record — and the
                    // one the server legitimately needs to filter, count and orphan-check.
                    assertEquals(s.companyId(), rs.getInt(2));
                }
            }
            // The client is the only thing that can open it: the same key, same context.
            var back = s.vaultItemById(id);
            var plain = VaultPayload.parse(VaultCrypto.decryptText(key(), back.blob(),
                    VaultCrypto.aadItem(back.companyId())));
            assertEquals(app, plain.app());
            assertEquals(pass, plain.pass());
        }
    }

    @Test
    void plaintextIsRefusedAtTheDoor() {
        try (Db db = open()) {
            var s = new Service(db);
            s.vaultCreate(VaultCrypto.KDF, SALT, ITERS, VaultCrypto.checkBlob(key()));
            // The most obvious mistake a client can make: send the password as text.
            assertThrows(Bad.class, () -> s.createVaultItem(s.companyId(), "hunter2"),
                    "plaintext was accepted as a record");
            assertThrows(Bad.class, () -> s.createVaultItem(s.companyId(), ""),
                    "an empty blob was accepted");
            assertThrows(Bad.class, () -> s.updateVaultItem(1, "hunter2", 1),
                    "plaintext was accepted as an update");
        }
    }

    @Test
    void oneMasterPasswordOpensEveryCompany() {
        try (Db db = open()) {
            var s = new Service(db);
            var a = s.companyId();
            s.vaultCreate(VaultCrypto.KDF, SALT, ITERS, VaultCrypto.checkBlob(key()));
            s.createVaultItem(a, sealed(a, "Gmail", "ana", "aa"));
            var b = s.createCompany("Outra", "", "8").id();
            s.activate(b);
            s.createVaultItem(b, sealed(b, "Netflix", "ana", "bb"));

            // This is the chosen design, not a leak: unlocking once shows both tags.
            // What a user must never be able to do is *write* another company's tag —
            // the store takes the tag from the sealed payload, and the AAD binds it.
            assertEquals(2, s.vaultCount());
            assertEquals(1, s.vaultCount(a));
            assertEquals(1, s.vaultCount(b));
            var all = s.vaultItems(null);
            assertEquals(2, all.size());
            assertEquals(List.of(b, a), all.stream().map(VaultItem::companyId).toList());
            assertEquals(List.of("Netflix"), s.vaultItems(b).stream()
                    .map(i -> VaultPayload.parse(VaultCrypto.decryptText(key(), i.blob(),
                            VaultCrypto.aadItem(i.companyId()))).app()).toList());
            // A record sealed under one company's context cannot be re-opened under
            // another's, which is what makes the plaintext tag unforgeable.
            var mineBlob = sealed(a, "Banco", "ana", "secret");
            assertThrows(Bad.class, () -> VaultPayload.parse(VaultCrypto.decryptText(key(),
                    mineBlob, VaultCrypto.aadItem(b))), "a record for A opened under B's context");
        }
    }

    @Test
    void aStaleRevisionIsRefusedAndNothingIsLost() {
        try (Db db = open()) {
            var s = new Service(db);
            s.vaultCreate(VaultCrypto.KDF, SALT, ITERS, VaultCrypto.checkBlob(key()));
            var item = s.createVaultItem(s.companyId(), sealed(s.companyId(), "Gmail", "ana", "v1"));
            assertEquals(1, item.revision());
            var saved = s.updateVaultItem(item.id(), sealed(s.companyId(), "Gmail", "ana", "v2"), 1);
            assertEquals(2, saved.revision());
            // Two tabs on the same record: the second writer must not overwrite quietly.
            assertThrows(Conflict.class, () -> s.updateVaultItem(item.id(),
                    sealed(s.companyId(), "Gmail", "ana", "v3"), 1), "a stale write slipped through");
            assertEquals("v2", VaultPayload.parse(VaultCrypto.decryptText(key(), s.vaultItemById(item.id()).blob(),
                    VaultCrypto.aadItem(s.companyId()))).pass(), "the winner's value was lost");
        }
    }

    @Test
    void aRecordCannotBeMovedBetweenCompanies() {
        try (Db db = open()) {
            var s = new Service(db);
            s.vaultCreate(VaultCrypto.KDF, SALT, ITERS, VaultCrypto.checkBlob(key()));
            var item = s.createVaultItem(s.companyId(), sealed(s.companyId(), "Gmail", "ana", "aa"));
            var other = s.createCompany("Outra", "", "8").id();
            // The trigger is the last wall: the AAD already refuses to open the blob
            // under another company, and this refuses even to relabel the row.
            var blocked = assertThrows(Problema.class, () -> db.exec(
                    "UPDATE vault_item SET company_id = " + other + " WHERE id = " + item.id()),
                    "a credential was relabelled to another company");
            assertTrue(blocked.getMessage().contains("entre companies"),
                    "the wall came from somewhere else: " + blocked.getMessage());
            assertEquals(s.companyId(), s.vaultItemById(item.id()).companyId());
        }
    }

    @Test
    void aCompanyWithCredentialsCannotBeDeleted() {
        try (Db db = open()) {
            var s = new Service(db);
            var mine = s.companyId();
            s.vaultCreate(VaultCrypto.KDF, SALT, ITERS, VaultCrypto.checkBlob(key()));
            s.createVaultItem(mine, sealed(mine, "Gmail", "ana", "aa"));
            assertThrows(Bad.class, () -> s.deleteCompany(mine),
                    "deleting the company deleted the vault");
        }
    }

    @Test
    void rekeyReplacesEveryBlobOrNone() {
        try (Db db = open()) {
            var s = new Service(db);
            var c = s.companyId();
            s.vaultCreate(VaultCrypto.KDF, SALT, ITERS, VaultCrypto.checkBlob(key()));
            var one = s.createVaultItem(c, sealed(c, "Gmail", "ana", "aa"));
            var two = s.createVaultItem(c, sealed(c, "Netflix", "ana", "bb"));

            String newSalt = Salt.of(32);
            var rekeyed = s.vaultRekey(VaultCrypto.KDF, newSalt, ITERS,
                    VaultCrypto.checkBlob(newKey()),
                    List.of(new VaultRewrap(one.id(), reseal(one, newKey()), one.revision()),
                          new VaultRewrap(two.id(), reseal(two, newKey()), two.revision())));
            assertTrue(rekeyed.sealed());
            assertEquals(newSalt, rekeyed.salt());
            for (var i : s.vaultItems(null)) assertEquals(2, i.revision());
            // The old key is now dead: nothing in the database opens with it.
            assertThrows(Bad.class, () -> VaultCrypto.decryptText(key(), s.vaultItems(null).getFirst().blob(),
                    VaultCrypto.aadItem(c)), "the old master password still works");

            // A rekey with one stale revision must change nothing at all — a half-done
            // re-key would leave records that the new password cannot open.
            assertThrows(Conflict.class, () -> s.vaultRekey(VaultCrypto.KDF, Salt.of(32), ITERS,
                    VaultCrypto.checkBlob(newKey()),
                    List.of(new VaultRewrap(one.id(), reseal(one, newKey()), 1))),
                    "a partial re-key was committed");
            assertEquals(newSalt, s.vaultMeta().salt(), "the verifier moved on a failed re-key");
            assertNotNull(VaultCrypto.decryptText(newKey(), s.vaultItems(null).getFirst().blob(),
                    VaultCrypto.aadItem(c)));
        }
    }

    @Test
    void anImportInsertsAllRecordsOrNone() {
        try (Db db = open()) {
            var s = new Service(db);
            var c = s.companyId();
            s.vaultCreate(VaultCrypto.KDF, SALT, ITERS, VaultCrypto.checkBlob(key()));
            var good = List.of(new VaultDraft(c, sealed(c, "A", "a", "1")),
                               new VaultDraft(c, sealed(c, "B", "b", "2")));
            assertEquals(2, s.createVaultItems(good).size());

            var mixed = List.of(new VaultDraft(c, sealed(c, "C", "c", "3")), new VaultDraft(c, "plaintext"));
            assertThrows(Bad.class, () -> s.createVaultItems(mixed), "an unsealed row imported");
            assertEquals(2, s.vaultCount(), "a failed import left rows behind");

            var orphan = List.of(new VaultDraft(c + 999, sealed(c + 999, "D", "d", "4")));
            assertThrows(NotFound.class, () -> s.createVaultItems(orphan), "an orphan row imported");
            assertEquals(2, s.vaultCount(), "a failed orphan import left rows behind");
        }
    }

    @Test
    void deletingTheVaultForggetsEverything() {
        try (Db db = open()) {
            var s = new Service(db);
            var c = s.companyId();
            s.vaultCreate(VaultCrypto.KDF, SALT, ITERS, VaultCrypto.checkBlob(key()));
            s.createVaultItem(c, sealed(c, "Gmail", "ana", "aa"));
            s.createVaultItem(c, sealed(c, "Netflix", "ana", "bb"));
            s.deleteVaultItem(s.vaultItems(null).getFirst().id());
            assertEquals(1, s.vaultCount());
            // Wiping one company's tag leaves the other's untouched.
            var other = s.createCompany("Outra", "", "8").id();
            s.activate(other);
            s.createVaultItem(other, sealed(other, "Disney", "ana", "cc"));
            s.activate(c);
            // Only the record still tagged A goes; B's Disney stays untouched.
            assertEquals(1, s.deleteVaultCompany(c));
            assertEquals(1, s.vaultCount());
            assertEquals(1, s.vaultCount(other));
        }
    }

    @Test
    void upgradingFromV6BringsTheVaultAndKeepsTheTimesheet() throws Exception {
        String url = TestDb.freshUrl();
        // A v6-shaped database: today's schema minus the vault, version rolled back to 6,
        // with a punch already stored so "the upgrade kept my data" is testable.
        try (Db db = Db.open(dir, url)) {
            db.exec("DROP TABLE vault_item");
            db.exec("DROP TABLE vault_meta");
            db.exec("UPDATE schema_migrations SET version = 6");
            assertEquals(6, db.migrationVersion());
            Service s = new Service(db);
            s.create(LocalDate.of(2026, 5, 20), LocalTime.of(8, 0), LocalTime.of(12, 0), "antes", false, true, true);
            assertEquals(1, s.count());
        }
        try (Db db = Db.open(dir, url)) {
            assertEquals(Migrations.VERSION, db.migrationVersion());
            assertTrue(db.hasTable("vault_meta"));
            assertTrue(db.hasTable("vault_item"));
            var s = new Service(db);
            assertEquals(1, s.count());
            assertEquals("antes", s.recent(1, 0).getFirst().just());
            assertFalse(s.vaultMeta().sealed(), "an upgraded vault arrived already unlocked");
            // Sealing still works after the upgrade, and the app refuses records until then.
            assertThrows(ponto.VaultSealed.class, () -> s.createVaultItem(s.companyId(), sealed(1, "x", "y", "z")),
                    "records were accepted against a vault with no verifier");
            var sealedVault = s.vaultCreate(VaultCrypto.KDF, SALT, ITERS, VaultCrypto.checkBlob(key()));
            assertTrue(sealedVault.sealed(), "the upgrade left the vault impossible to seal");
            // The trigger arrives with the upgrade, not only with a fresh install.
            var stored = s.createVaultItem(s.companyId(), sealed(s.companyId(), "Gmail", "ana", "aa"));
            var other = s.createCompany("Outra", "", "8").id();
            var blocked = assertThrows(Problema.class, () -> db.exec(
                    "UPDATE vault_item SET company_id = " + other + " WHERE id = " + stored.id()),
                    "the upgraded database accepted a relabelled credential");
            assertTrue(blocked.getMessage().contains("entre companies"), blocked.getMessage());
        }
    }

    private static String reseal(VaultItem i, javax.crypto.SecretKey k) {
        var p = VaultPayload.parse(VaultCrypto.decryptText(key(), i.blob(), VaultCrypto.aadItem(i.companyId())));
        return VaultCrypto.encryptText(k, VaultPayload.json(p), VaultCrypto.aadItem(i.companyId()));
    }

    private static javax.crypto.SecretKey newKey() {
        return VaultCrypto.deriveKey("novo master", VaultCrypto.decode(Salt.of(32)), ITERS);
    }

    /** Small helper so the tests do not each repeat the base64 boilerplate. */
    private static final class Salt {
        static String of(int n) {
            byte[] b = new byte[n];
            for (int i = 0; i < n; i++) b[i] = (byte) (i * 7 + 1);
            return java.util.Base64.getEncoder().encodeToString(b);
        }
    }
}
