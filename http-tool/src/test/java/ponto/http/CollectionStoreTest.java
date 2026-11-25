package ponto.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CollectionStoreTest {

    @TempDir
    Path dir;

    @Test
    void roundTrip() throws Exception {
        CollectionStore store = new CollectionStore(dir.resolve("collection.json"));
        Map<String, Object> empty = store.load();
        assertEquals("Minha collection", empty.get("name"));
        assertTrue(((List<?>) empty.get("items")).isEmpty());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "API Prod");
        data.put("activeId", "r1");
        data.put("items", List.of(Map.of("id", "r1", "method", "GET", "url", "https://x.test")));
        Map<String, Object> saved = store.save(data);
        assertNotNull(saved.get("revision"));

        Map<String, Object> loaded = store.load();
        assertEquals("API Prod", loaded.get("name"));
        assertEquals("r1", loaded.get("activeId"));
        assertEquals(1, ((List<?>) loaded.get("items")).size());
        assertEquals(saved.get("revision"), loaded.get("revision"));
    }
}
