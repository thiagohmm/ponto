package ponto.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ponto.TestDb;

import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DiarioTest {
    @TempDir Path dir;

    @Test void snapshotAggregatesAndDigestUpsertsPerCompanyDay() {
        try (Db db = TestDb.open(dir)) {
            Service s = new Service(db);
            LocalDate dia = LocalDate.of(2026, 11, 13);
            s.create(dia, LocalTime.of(9, 0), LocalTime.of(12, 0), "manhã", false, false, false);
            s.notes().create("Ideia", "corpo da nota");
            int col = s.kanban().columns().getFirst().id();
            s.kanban().createCard("Cartão diário", "fazer", col);

            Map<String, Object> snap = s.diario(dia);
            assertEquals("13/11/2026", snap.get("dia"));
            @SuppressWarnings("unchecked")
            List<?> entries = (List<?>) snap.get("entries");
            @SuppressWarnings("unchecked")
            List<?> notes = (List<?>) snap.get("notes");
            @SuppressWarnings("unchecked")
            List<?> cards = (List<?>) snap.get("kanbanCards");
            @SuppressWarnings("unchecked")
            Map<String, Object> charts = (Map<String, Object>) snap.get("charts");
            assertEquals(1, entries.size());
            assertFalse(notes.isEmpty());
            assertFalse(cards.isEmpty());
            assertEquals(180L, ((Number) charts.get("dayMins")).longValue());
            assertNull(snap.get("digest"));

            Assist off = new Assist(URI.create("http://127.0.0.1:9"), "qwen2.5:0.5b", false);
            Map<String, Object> dig = s.gerarResumoDiario(dia, off);
            assertEquals("fallback", dig.get("model"));
            assertFalse(dig.get("summary").toString().isBlank());

            Map<String, Object> again = s.diario(dia);
            assertNotNull(again.get("digest"));
            @SuppressWarnings("unchecked")
            Map<String, Object> saved = (Map<String, Object>) again.get("digest");
            assertEquals(dig.get("summary"), saved.get("summary"));

            // regenerate overwrites
            Map<String, Object> second = s.gerarResumoDiario(dia, off);
            assertEquals("fallback", second.get("model"));
        }
    }

    @Test void schemaUpgradeFromV5CreatesDayDigest() throws Exception {
        String url = TestDb.freshUrl();
        // Build a v5-looking DB: full current schema then roll version back and drop day_digest.
        try (Db db = Db.open(dir, url)) {
            assertEquals(Migrations.VERSION, db.migrationVersion());
            assertTrue(db.hasTable("day_digest"));
            db.exec("DROP TABLE day_digest");
            db.exec("UPDATE schema_migrations SET version = 5");
            assertEquals(5, db.migrationVersion());
            assertFalse(db.hasTable("day_digest"));
        }
        try (Db db = Db.open(dir, url)) {
            assertEquals(6, db.migrationVersion());
            assertTrue(db.hasTable("day_digest"));
            Service s = new Service(db);
            Map<String, Object> dig = s.gerarResumoDiario(LocalDate.of(2026, 1, 2),
                    new Assist(URI.create("http://127.0.0.1:9"), "x", false));
            assertNotNull(dig.get("summary"));
        }
    }
}
