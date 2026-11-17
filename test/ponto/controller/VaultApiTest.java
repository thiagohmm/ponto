package ponto.controller;

import ponto.Bad;
import ponto.app.Service;
import ponto.infra.Db;
import ponto.model.VaultCrypto;
import ponto.model.VaultPayload;
import ponto.view.Json3;
import ponto.TestDb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The vault over HTTP — the boundary where "zero knowledge" can actually be falsified.
 *
 * What is checked here is not whether the crypto works (that is {@code VaultCryptoTest})
 * but what the wire is allowed to carry: sealed blobs, a company tag and a revision.
 * Two tests make the design's central claims visible:
 *
 * <ul>
 * <li>{@link #theServerAcceptsABlobItCannotRead} — a blob sealed with a completely
 * different key is stored with no complaint and no trace. Nothing on the server can
 * open it, so a wrong key is indistinguishable from a right one: the vault's integrity
 * lives in the client, and no oracle lives here.</li>
 * <li>{@link #noWriteEverCarriesPlaintext} — a bare password in the body is refused,
 * and no response body echoes the secret back.</li>
 * </ul>
 *
 * The rest is the contract the SPA codes against: 423 + {@code need:"create"} while
 * the vault is unsealed, 409 on a stale revision or a vault that already exists, and
 * the {@code companyId} the app's before() hook demands of every other route too.
 */
final class VaultApiTest {

    @TempDir Path dir;

    private static final String KEY_B64 = "ymbU6Vwmj6pk+tnohpK36g5LAcqE9Ns9cJioIM6I27Q=";
    private static final String SALT = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    /** A stranger's key: 32 bytes of 7s, unrelated to the vault's. */
    private static final String OTHER_B64 = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=";

    private static final String SECRET = "Tr0y-P@ss-Word-9";
    private static final String APP = "Portal oficcional Globo";
    private static final String USER = "ana.torres@ex.com";
    private static final int ITERS = 100_000;

    private final HttpClient client = HttpClient.newHttpClient();

    private static javax.crypto.SecretKey key(String b64) { return new javax.crypto.spec.SecretKeySpec(VaultCrypto.decode(b64), "AES"); }
    private javax.crypto.SecretKey sealedKey() { return key(KEY_B64); }
    private javax.crypto.SecretKey otherKey() { return key(OTHER_B64); }

    private String blob(int company, javax.crypto.SecretKey k) {
        return VaultCrypto.encryptText(k,
                VaultPayload.json(new VaultPayload.Plain(APP, USER, SECRET, "", company)),
                VaultCrypto.aadItem(company));
    }

    private HttpResponse<String> call(String base, String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Map<?,?> map(String body) { return new Json3().fromJsonString(body, Map.class); }
    private List<Map<?, ?>> items(String base, String query) throws Exception {
        return (List<Map<?, ?>>) map(call(base, "GET", "/api/vault/items?" + query, null).body()).get("items");
    }

    /** Seal the vault so the tests below can be about records instead of the handshake. */
    private void seal(String base, int company) throws Exception {
        var made = call(base, "POST", "/api/vault?companyId=" + company,
                "{\"kdf\":\"" + VaultCrypto.KDF + "\",\"salt\":\"" + SALT + "\",\"iterations\":" + ITERS
                + ",\"check\":\"" + VaultCrypto.checkBlob(sealedKey()) + "\"}");
        assertEquals(201, made.statusCode(), made.body());
    }

    @Test
    void anUnsealedVaultAnswers423WithTheNextStep() throws Exception {
        try (Db db = TestDb.open(dir)) {
            var service = new Service(db);
            var app = new Web(service, 0).application().start("127.0.0.1", 0);
            String base = "http://127.0.0.1:" + app.port();
            int company = service.companyId();
            try {
                Map<?,?> meta = map(call(base, "GET", "/api/vault?companyId=" + company, null).body());
                assertEquals("false", String.valueOf(meta.get("sealed")), "a fresh vault looked unlocked");
                assertEquals("", meta.get("salt"), "there is no salt before the vault is sealed");
                assertEquals(0, meta.get("items"));

                // "Save this before you have a vault" answers 423 + need, never a 200
                // that would strand the record against no verifier at all.
                var blocked = call(base, "POST", "/api/vault/items?companyId=" + company,
                        "{\"companyId\":" + company + ",\"blob\":\"" + blob(company, sealedKey()) + "\"}");
                assertEquals(423, blocked.statusCode(), "unsealed write returned " + blocked.statusCode());
                assertEquals("create", map(blocked.body()).get("need"));
                assertNotNull(blocked.headers().firstValue("X-Request-ID").orElse(null));
            } finally { app.stop(); }
        }
    }

    @Test
    void sealingIsATurnstileAndNeverAReset() throws Exception {
        try (Db db = TestDb.open(dir)) {
            var service = new Service(db);
            var app = new Web(service, 0).application().start("127.0.0.1", 0);
            String base = "http://127.0.0.1:" + app.port();
            int company = service.companyId();
            try {
                String check = VaultCrypto.checkBlob(sealedKey());
                var made = call(base, "POST", "/api/vault?companyId=" + company,
                        "{\"kdf\":\"" + VaultCrypto.KDF + "\",\"salt\":\"" + SALT + "\",\"iterations\":" + ITERS
                        + ",\"check\":\"" + check + "\"}");
                assertEquals(201, made.statusCode());
                Map<?,?> m = map(made.body());
                assertEquals(SALT, m.get("salt"), "the client must get its own salt back");
                assertEquals(ITERS, m.get("iterations"));
                assertEquals(check, m.get("check"));
                assertEquals(VaultCrypto.KDF, m.get("kdf"));

                // A second seal would replace the verifier and lock every record out.
                assertEquals(409, call(base, "POST", "/api/vault?companyId=" + company,
                        "{\"kdf\":\"" + VaultCrypto.KDF + "\",\"salt\":\"" + SALT + "\",\"iterations\":" + ITERS
                        + ",\"check\":\"" + check + "\"}").statusCode());

                // A weak iteration count is refused at the door, not trusted.
                var weak = call(base, "POST", "/api/vault?companyId=" + company,
                        "{\"kdf\":\"" + VaultCrypto.KDF + "\",\"salt\":\"" + SALT + "\",\"iterations\":10,\"check\":\"" + check + "\"}");
                assertEquals(400, weak.statusCode(), "PBKDF2 with 10 iterations was accepted");

                // A foreign kdf means a key we never agreed to derive.
                var foreign = call(base, "POST", "/api/vault?companyId=" + company,
                        "{\"kdf\":\"argon2\",\"salt\":\"" + SALT + "\",\"iterations\":" + ITERS + ",\"check\":\"" + check + "\"}");
                assertEquals(400, foreign.statusCode(), "an unagreed kdf was accepted");
            } finally { app.stop(); }
        }
    }

    @Test
    void noWriteEverCarriesPlaintext() throws Exception {
        try (Db db = TestDb.open(dir)) {
            var service = new Service(db);
            var app = new Web(service, 0).application().start("127.0.0.1", 0);
            String base = "http://127.0.0.1:" + app.port();
            int company = service.companyId();
            try {
                seal(base, company);

                // Not sealed, just the password itself: 400. The store would have
                // refused it too; the route catches it first.
                var bare = call(base, "POST", "/api/vault/items?companyId=" + company,
                        "{\"companyId\":" + company + ",\"blob\":\"" + SECRET + "\"}");
                assertEquals(400, bare.statusCode(), "a bare password was stored");

                var created = call(base, "POST", "/api/vault/items?companyId=" + company,
                        "{\"companyId\":" + company + ",\"blob\":\"" + blob(company, sealedKey()) + "\"}");
                assertEquals(201, created.statusCode());
                Map<?,?> item = map(created.body());
                assertEquals(1, item.get("revision"));
                assertEquals(company, item.get("companyId"));
                assertFalse(created.body().contains(SECRET), "the response echoed the password");
                assertFalse(created.body().contains(APP), "the response echoed the application name");
                assertFalse(created.body().contains(USER), "the response echoed the user");
                int id = (int) item.get("id");

                // The client is the only opener.
                var row = items(base, "companyId=" + company).getFirst();
                assertEquals(VaultPayload.json(new VaultPayload.Plain(APP, USER, SECRET, "", company)),
                        VaultCrypto.decryptText(sealedKey(), String.valueOf(row.get("blob")),
                                VaultCrypto.aadItem(company)));

                var gone = call(base, "DELETE", "/api/vault/items/" + id + "?companyId=" + company, null);
                assertEquals(200, gone.statusCode());
                assertTrue(items(base, "companyId=" + company).isEmpty());
            } finally { app.stop(); }
        }
    }

    @Test
    void theServerAcceptsABlobItCannotRead() throws Exception {
        try (Db db = TestDb.open(dir)) {
            var service = new Service(db);
            var app = new Web(service, 0).application().start("127.0.0.1", 0);
            String base = "http://127.0.0.1:" + app.port();
            int company = service.companyId();
            try {
                seal(base, company);
                // Sealed with a stranger's key. 201 — the server cannot tell, and that
                // is the point: there is no oracle here for an attacker to attack.
                var stored = call(base, "POST", "/api/vault/items?companyId=" + company,
                        "{\"companyId\":" + company + ",\"blob\":\"" + blob(company, otherKey()) + "\"}");
                assertEquals(201, stored.statusCode(), "the server claimed to judge the ciphertext");

                // The owner cannot open it either: GCM's tag refuses. Integrity survives,
                // and the stranger's blob is simply junk.
                var row = items(base, "companyId=" + company).getFirst();
                assertThrows(Bad.class, () -> VaultCrypto.decryptText(sealedKey(),
                        String.valueOf(row.get("blob")), VaultCrypto.aadItem(company)),
                        "a blob sealed by someone else opened for the owner");
            } finally { app.stop(); }
        }
    }

    @Test
    void aStaleRevisionIsAConflictAndNothingSilentlyWins() throws Exception {
        try (Db db = TestDb.open(dir)) {
            var service = new Service(db);
            var app = new Web(service, 0).application().start("127.0.0.1", 0);
            String base = "http://127.0.0.1:" + app.port();
            int company = service.companyId();
            try {
                seal(base, company);
                var created = map(call(base, "POST", "/api/vault/items?companyId=" + company,
                        "{\"companyId\":" + company + ",\"blob\":\"" + blob(company, sealedKey()) + "\"}").body());
                int id = (int) created.get("id");

                // A write without a revision would let two tabs overwrite each other.
                var noRev = call(base, "PUT", "/api/vault/items/" + id + "?companyId=" + company,
                        "{\"blob\":\"" + blob(company, sealedKey()) + "\"}");
                assertEquals(400, noRev.statusCode(), "an unversioned write was accepted");

                var ok = call(base, "PUT", "/api/vault/items/" + id + "?companyId=" + company,
                        "{\"blob\":\"" + blob(company, sealedKey()) + "\",\"revision\":1}");
                assertEquals(200, ok.statusCode());
                assertEquals(2, map(ok.body()).get("revision"));

                // The stale copy now conflicts instead of quietly winning.
                assertEquals(409, call(base, "PUT", "/api/vault/items/" + id + "?companyId=" + company,
                        "{\"blob\":\"" + blob(company, sealedKey()) + "\",\"revision\":1}").statusCode(),
                        "the second writer overwrote the first quietly");
            } finally { app.stop(); }
        }
    }

    @Test
    void oneUnlockShowsEveryCompanyAndWipesOnlyOne() throws Exception {
        try (Db db = TestDb.open(dir)) {
            var service = new Service(db);
            var app = new Web(service, 0).application().start("127.0.0.1", 0);
            String base = "http://127.0.0.1:" + app.port();
            int a = service.companyId();
            int b = service.createCompany("Outra", "", "8").id();
            try {
                seal(base, a);
                call(base, "POST", "/api/vault/items?companyId=" + a,
                        "{\"companyId\":" + a + ",\"blob\":\"" + blob(a, sealedKey()) + "\"}");
                call(base, "POST", "/api/vault/items?companyId=" + b,
                        "{\"companyId\":" + b + ",\"blob\":\"" + blob(b, sealedKey()) + "\"}");

                // One unlock, both tags: the shape the user asked for.
                assertEquals(2, items(base, "companyId=" + a).size(), "the vault hid the other company");
                assertEquals(1, items(base, "companyId=" + a + "&company=" + b).size());

                // Forgetting one company leaves the other intact.
                var wiped = map(call(base, "DELETE", "/api/vault/company?companyId=" + b, null).body());
                assertEquals(1, wiped.get("deleted"));
                assertEquals(b, wiped.get("company"));
                assertEquals(1, items(base, "companyId=" + a).size());
            } finally { app.stop(); }
        }
    }

    @Test
    void rekeyMovesTheWholeVaultInOneRequest() throws Exception {
        try (Db db = TestDb.open(dir)) {
            var service = new Service(db);
            var app = new Web(service, 0).application().start("127.0.0.1", 0);
            String base = "http://127.0.0.1:" + app.port();
            int company = service.companyId();
            try {
                seal(base, company);
                var row = call(base, "POST", "/api/vault/items?companyId=" + company,
                        "{\"companyId\":" + company + ",\"blob\":\"" + blob(company, sealedKey()) + "\"}").body();
                int id = (int) map(row).get("id");

                String newSalt = "CQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQk=";
                var rekeyed = call(base, "POST", "/api/vault/rekey?companyId=" + company,
                        "{\"kdf\":\"" + VaultCrypto.KDF + "\",\"salt\":\"" + newSalt + "\",\"iterations\":" + ITERS
                        + ",\"check\":\"" + VaultCrypto.checkBlob(otherKey())
                        + "\",\"items\":[{\"id\":" + id + ",\"blob\":\"" + blob(company, otherKey()) + "\",\"revision\":1}]}");
                assertEquals(200, rekeyed.statusCode(), rekeyed.body());
                Map<?,?> m = map(rekeyed.body());
                assertEquals(newSalt, m.get("salt"), "the new salt did not replace the old one");
                assertEquals(1, m.get("items"));

                // With the new key the record opens; with the old one it is gone.
                var row2 = items(base, "companyId=" + company).getFirst();
                assertEquals(SECRET, map(VaultCrypto.decryptText(otherKey(), String.valueOf(row2.get("blob")),
                        VaultCrypto.aadItem(company))).get("pass"));
            } finally { app.stop(); }
        }
    }

    @Test
    void anImportIsAllOrNothing() throws Exception {
        try (Db db = TestDb.open(dir)) {
            var service = new Service(db);
            var app = new Web(service, 0).application().start("127.0.0.1", 0);
            String base = "http://127.0.0.1:" + app.port();
            int a = service.companyId();
            int b = service.createCompany("Importada", "", "8").id();
            try {
                seal(base, a);
                var empty = call(base, "POST", "/api/vault/items/bulk?companyId=" + a, "{\"items\":[]}");
                assertEquals(400, empty.statusCode());

                // Both companies in one file: the tags survive, the plaintext does not.
                var bulk = call(base, "POST", "/api/vault/items/bulk?companyId=" + a,
                        "{\"items\":[" + item(a, sealedKey()) + "," + item(b, sealedKey()) + "]}");
                assertEquals(201, bulk.statusCode(), bulk.body());
                Map<?,?> done = map(bulk.body());
                assertEquals(2, done.get("imported"));
                var rows = items(base, "companyId=" + a);
                assertEquals(2, rows.size());

                // A record aimed at a company that does not exist rolls the whole
                // file back -- not half an import and a clear error nobody reads.
                var orphan = call(base, "POST", "/api/vault/items/bulk?companyId=" + a,
                        "{\"items\":[" + item(a, sealedKey()) + "," + item(99999, sealedKey()) + "]}");
                assertEquals(404, orphan.statusCode(), "an orphan import was accepted: " + orphan.body());
                assertEquals(2, items(base, "companyId=" + a).size(), "half of a failed import was kept");
            } finally { app.stop(); }
        }
    }

    /** One sealed record as JSON text, ready to drop into a bulk body. */
    private String item(int company, javax.crypto.SecretKey k) {
        return "{\"companyId\":" + company + ",\"blob\":\"" + blob(company, k) + "\"}";
    }

    @Test
    void theVaultIsNotAHoleInTheAppsOwnRules() throws Exception {
        try (Db db = TestDb.open(dir)) {
            var service = new Service(db);
            var app = new Web(service, 0).application().start("127.0.0.1", 0);
            String base = "http://127.0.0.1:" + app.port();
            try {
                // The before() hook that guards every other /api/ route guards these too.
                assertEquals(400, call(base, "GET", "/api/vault", null).statusCode(), "no companyId, no refusal");
                assertEquals(400, call(base, "GET", "/api/vault/items", null).statusCode());
                assertEquals(400, call(base, "POST", "/api/vault/items", "{}").statusCode());
                var malformed = call(base, "POST", "/api/vault?companyId=1", "{invalid");
                assertEquals(400, malformed.statusCode());
                assertNotNull(malformed.headers().firstValue("X-Request-ID").orElse(null));
            } finally { app.stop(); }
        }
    }
}
