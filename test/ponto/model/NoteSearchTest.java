package ponto.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ponto.TestDb;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NoteSearchTest {
    @TempDir Path dir;
    private Db open(String name) { return TestDb.open(dir); }

    static class Mem implements ObjectStore {
        public boolean up() { return true; }
        public void ensureBucket() { }
        public void put(String key, byte[] bytes, String type) { }
        public byte[] get(String key) { return new byte[0]; }
        public void delete(String key) { }
    }

    @Test void listFindsEveryWordAcrossTitleBodyTagAndAttachment() {
        try (Db db = open("search.db"); Files files = new Files(db, db.lock, new Mem())) {
            var s = new Service(db);
            var tag = s.notes().createTag("urgente", null);
            // "Ponto" in the title, "folha" in the body, "urgente" as a tag
            s.notes().create("Ponto", "folha de pagamento", null, List.of(tag.id()));
            // "reunião" in the title, "contrato" only in the attachment name
            var n2 = s.notes().create("reunião", "agenda", null, null);
            files.add(n2.id(), "contrato.pdf", "application/pdf", new byte[] { 1 });
            // control note with none of the searched words
            s.notes().create("outro", "nada aqui", null, null);

            // every word must appear somewhere; order does not matter
            assertEquals(1, s.notes().list("ponto folha").size());
            assertEquals(1, s.notes().list("folha ponto").size());
            // case-insensitive: "PONTO" matches "Ponto"
            assertEquals(1, s.notes().list("PONTO").size());
            // accent-insensitive: "reuniao" matches "reunião"
            assertEquals(1, s.notes().list("reuniao").size());
            // a word that lives only in an attachment name
            assertEquals(1, s.notes().list("contrato").size());
            // a word that lives only in a tag
            assertEquals(1, s.notes().list("urgente").size());
            // two words in different fields of the same note (title + attachment)
            assertEquals(1, s.notes().list("reuniao contrato").size());
            // a missing word excludes everything
            assertEquals(0, s.notes().list("ponto inexistente").size());
            // blank query returns all notes
            assertEquals(3, s.notes().list("").size());
        }
    }
}
