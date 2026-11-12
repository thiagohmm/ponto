package ponto.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ponto.Conflict;
import ponto.NotFound;
import ponto.Problema;
import ponto.TestDb;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class IntegrityTest {
    @TempDir Path dir;
    private Db open(String name) { return TestDb.open(dir); }

    @Test void schemaInitPreservesDataAndIsRepeatable() throws Exception {
        String url = TestDb.freshUrl();
        try (Db db = Db.open(dir, url)) {
            assertTrue(db.fresh);
            Service s = new Service(db);
            s.setConfig("Ana", null, "6", false, x -> {});
            try (var q = db.conn().createStatement()) {
                q.execute("INSERT INTO ponto(id,dia,tin,tout,just,company_id) VALUES(7,'2026-05-20','08:00','16:00','legado'," + s.companyId() + ")");
                q.execute("SELECT setval(pg_get_serial_sequence('ponto','id'), 99, true)");
            }
        }
        for (int i = 0; i < 2; i++) try (Db db = Db.open(dir, url)) {
            assertFalse(db.fresh);
            Service s = new Service(db);
            assertEquals("legado", s.byId(7).just());
            assertEquals(360, s.dailyMinutes());
            assertEquals(1, s.count());
            assertEquals(Migrations.VERSION, db.migrationVersion());
        }
        try (Db db = Db.open(dir, url)) {
            var s = new Service(db);
            assertTrue(s.create(LocalDate.of(2026, 5, 22), null, null, "novo", false, false, false).id() > 99);
        }
    }

    @Test void failedMigrationRollsBackDdlAndPreservesExistingRows() throws Exception {
        String url = TestDb.freshUrl();
        String schema = TestDb.schemaOf(url);
        try (var c = DriverManager.getConnection(url); var q = c.createStatement()) {
            // A pre-existing `note` table makes CREATE TABLE note fail midway through schema(),
            // exercising PostgreSQL transactional DDL rollback of the preceding CREATE TABLEs.
            q.execute("CREATE TABLE note(id INT PRIMARY KEY, title TEXT, folder_id INT)");
            q.execute("INSERT INTO note(id,title,folder_id) VALUES(3,'preservar',999)");
        }
        assertThrows(Problema.class, () -> Db.open(dir, url));
        try (var c = DriverManager.getConnection(url); var q = c.createStatement()) {
            try (var rs = q.executeQuery("SELECT count(*) FROM information_schema.tables "
                    + "WHERE table_schema='" + schema + "' AND table_name='schema_migrations'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
            try (var rs = q.executeQuery("SELECT count(*) FROM information_schema.tables "
                    + "WHERE table_schema='" + schema + "' AND table_name='ponto'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
            try (var rs = q.executeQuery("SELECT title,folder_id FROM note WHERE id=3")) {
                assertTrue(rs.next());
                assertEquals("preservar", rs.getString(1));
                assertEquals(999, rs.getInt(2));
            }
        }
    }

    @Test void independentContextsAndCalendarsSurviveConcurrentAccess() throws Exception {
        try (Db db = open("scope")) {
            Service root = new Service(db);
            int a = root.companyId(), b = root.companies().create("B", "", "8").id();
            Service sa = root.forCompany(a), sb = root.forCompany(b);
            sa.setConfig(null, "20/05/2026", "6", false, x -> {});
            var day = LocalDate.of(2026, 5, 20);
            var note = sa.notes().create("A", "A");
            assertThrows(NotFound.class, () -> sb.notes().delete(note.id()));
            assertEquals(1, sa.notes().list(null).size());
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for (int i = 0; i < 40; i++) {
                    Service s = i % 2 == 0 ? sa : sb;
                    tasks.add(pool.submit(() -> {
                        assertEquals(s == sa, s.holiday(day).contains("sua folga"));
                        assertEquals(s == sa ? 360 : 480, s.dailyMinutes());
                        s.create(day, null, null, s == sa ? "A" : "B", false, false, false);
                    }));
                }
                for (var task : tasks) task.get();
            }
            assertEquals(20, sa.count());
            assertEquals(20, sb.count());
            assertEquals(a, root.companyId());
            root.closeResources();
        }
    }

    @Test void noteCreationTagsAndFolderUpdatesAreAtomic() {
        try (Db db = open("atomic")) {
            var s = new Service(db);
            var tag = s.notes().createTag("tag", null);
            var n = s.notes().create("nota", "texto", null, List.of(tag.id()));
            assertThrows(NotFound.class, () -> s.notes().setTags(n.id(), List.of(999)));
            assertEquals(List.of(tag.id()), s.notes().byId(n.id()).tagIds());
            assertThrows(NotFound.class, () -> s.notes().create("incompleta", "", null, List.of(999)));
            assertEquals(1, s.notes().list(null).size());
            var f = s.notes().createFolder("original", null);
            assertThrows(Problema.class, () -> s.transaction(() -> {
                s.notes().renameFolder(f.id(), "alterado");
                return s.notes().moveFolder(f.id(), 999);
            }));
            assertEquals("original", s.notes().folders().getFirst().name());
        }
    }

    @Test void revisionConflictPreservesNewerText() {
        try (Db db = open("revision")) {
            var notes = new Service(db).notes();
            var n = notes.create("titulo", "primeiro");
            var next = notes.update(n.id(), "titulo", "segundo", n.revision());
            assertEquals(n.revision() + 1, next.revision());
            assertThrows(Conflict.class, () -> notes.update(n.id(), "titulo", "antigo", n.revision()));
            assertEquals("segundo", notes.byId(n.id()).body());
        }
    }

    @Test void backupProducesRestorablePgDump() throws Exception {
        Path dump = dir.resolve("snapshot.dump");
        String sourceUrl = TestDb.freshUrl();
        String sourceSchema = TestDb.schemaOf(sourceUrl);
        try (Db db = Db.open(dir, sourceUrl)) {
            new Service(db).notes().create("PG", "confirmado");
            backupWithFallback(db, dump);
            new Service(db).notes().create("depois", "fora do backup");
        }
        // Drop and recreate the source schema, restore the dump into it, and verify only committed pre-snapshot rows exist.
        try (var c = DriverManager.getConnection(TestDb.baseUrl()); var q = c.createStatement()) {
            q.execute("DROP SCHEMA " + sourceSchema + " CASCADE");
        }
        restoreDump(TestDb.baseUrl(), dump);
        try (Db db = Db.open(dir, sourceUrl)) {
            var notes = new Service(db).notes().list(null);
            assertEquals(1, notes.size());
            assertEquals("confirmado", notes.getFirst().body());
        }
    }

    private static void backupWithFallback(Db db, Path dump) throws Exception {
        try {
            db.backup(dump);
        } catch (Problema e) {
            // Local dev hosts without pg_dump installed fall back to the test container's pg_dump.
            String pgUrl = Db.libpqUrl(db.url).replaceAll("@(127\\.0\\.0\\.1|localhost):\\d+/", "@127.0.0.1:5432/");
            String sc = Db.currentSchema(db.url);
            var cmd = new java.util.ArrayList<>(List.of("docker", "exec", TestDb.containerName(),
                    "pg_dump", "--dbname=" + pgUrl, "--format=custom", "--no-password"));
            if (!sc.isEmpty()) cmd.add("--schema=" + sc);
            int code = new ProcessBuilder(cmd).redirectOutput(dump.toFile()).start().waitFor();
            assertEquals(0, code, "pg_dump via docker falhou");
        }
    }

    private static void restoreDump(String baseJdbcUrl, Path dump) throws Exception {
        String pgUrl = Db.libpqUrl(baseJdbcUrl);
        try {
            int code = new ProcessBuilder("pg_restore", "--dbname=" + pgUrl, "--no-owner", "--no-privileges", dump.toString())
                    .redirectErrorStream(true).start().waitFor();
            if (code == 0) return;
        } catch (java.io.IOException ignored) { /* fallback to container */ }
        String inContainer = pgUrl.replaceAll("@(127\\.0\\.0\\.1|localhost):\\d+/", "@127.0.0.1:5432/");
        String remote = "/tmp/" + dump.getFileName();
        assertEquals(0, new ProcessBuilder("docker", "cp", dump.toString(), TestDb.containerName() + ":" + remote).start().waitFor());
        try {
            assertEquals(0, new ProcessBuilder("docker", "exec", TestDb.containerName(),
                    "pg_restore", "--dbname=" + inContainer, "--no-owner", "--no-privileges", remote)
                    .redirectErrorStream(true).start().waitFor());
        } finally {
            new ProcessBuilder("docker", "exec", TestDb.containerName(), "rm", "-f", remote).start().waitFor();
        }
    }

    @Test void clockAndBalanceBoundariesAreDeterministic() throws Exception {
        Clock fixed = Clock.fixed(Instant.parse("2026-06-01T01:00:00Z"), ZoneId.of("America/Sao_Paulo"));
        try (var clock = Time.using(fixed); Db db = open("clock")) {
            assertEquals(LocalDate.of(2026, 5, 31), Time.today());
            assertEquals("2026-05", new Service(db).bank().ym().toString());
            assertEquals(Time.today(), Parse.date("today"));
        }
        for (int raw : new int[]{-6, -5, -4, 0, 4, 5, 6}) {
            assertEquals(Math.abs(raw) <= 5 ? 0 : raw, BankRules.day(480 + raw, 480, false, false, false, true));
            assertEquals(raw, BankRules.day(480 + raw, 480, true, false, false, true));
        }
        assertEquals(60, BankRules.day(60, 480, false, false, true, true));
        assertEquals(0, BankRules.day(0, 480, false, true, true, false));
        assertEquals(-480, BankRules.day(0, 480, true, false, true, false));
    }
}
