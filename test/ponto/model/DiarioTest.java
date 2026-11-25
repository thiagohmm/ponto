package ponto.model;

import ponto.app.Service;

import ponto.infra.Migrations;

import ponto.infra.Assist;

import ponto.infra.Db;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ponto.TestDb;
import ponto.model.Str;

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
            // Notes/cards are stamped with now(); diary filters by that day.
            LocalDate dia = LocalDate.now();
            s.create(dia, LocalTime.of(9, 0), LocalTime.of(12, 0), "manhã", false, false, false);
            s.createNote("Ideia", "corpo da nota");
            int col = s.kanbanColumns().getFirst().id();
            s.createKanbanCard("Cartão diário", "fazer", col);

            Map<String, Object> snap = s.diario(dia);
            assertEquals(Str.d(dia), snap.get("dia"));
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
            var dig = s.gerarResumoDiario(dia, off);
            assertEquals("fallback", dig.model());
            assertFalse(dig.summary().isBlank());

            Map<String, Object> again = s.diario(dia);
            assertNotNull(again.get("digest"));
            @SuppressWarnings("unchecked")
            Map<String, Object> saved = (Map<String, Object>) again.get("digest");
            assertEquals(dig.summary(), saved.get("summary"));

            // regenerate overwrites
            var second = s.gerarResumoDiario(dia, off);
            assertEquals("fallback", second.model());
        }
    }

    @Test void schemaUpgradeFromV5CreatesDayDigest() throws Exception {
        String url = TestDb.freshUrl();
        // Build a genuinely v5-shaped DB: full current schema, then remove what v6+ add
        // and roll the recorded version back. Leaving the newer tables behind
        // would make the upgrade "pass" by skipping them.
        try (Db db = Db.open(dir, url)) {
            assertEquals(Migrations.VERSION, db.migrationVersion());
            assertTrue(db.hasTable("day_digest"));
            db.exec("DROP TABLE day_digest");
            db.exec("DROP TABLE kanban_activity");
            db.exec("DROP TABLE vault_item");
            db.exec("DROP TABLE vault_meta");
            db.exec("DROP TABLE http_client_collection");
            db.exec("UPDATE schema_migrations SET version = 5");
            assertEquals(5, db.migrationVersion());
            assertFalse(db.hasTable("day_digest"));
        }
        try (Db db = Db.open(dir, url)) {
            // Steps: v6 day_digest, v7 vault, v8 kanban_activity, v9 http_client_collection.
            assertEquals(Migrations.VERSION, db.migrationVersion());
            assertTrue(db.hasTable("day_digest"));
            assertTrue(db.hasTable("vault_meta"));
            assertTrue(db.hasTable("vault_item"));
            assertTrue(db.hasTable("http_client_collection"));
            Service s = new Service(db);
            var dig = s.gerarResumoDiario(LocalDate.of(2026, 1, 2),
                    new Assist(URI.create("http://127.0.0.1:9"), "x", false));
            assertNotNull(dig.summary());
        }
    }
}
