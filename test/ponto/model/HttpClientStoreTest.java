package ponto.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ponto.TestDb;
import ponto.app.Service;
import ponto.infra.Db;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HttpClientStoreTest {

    @TempDir
    Path dir;

    @Test
    void saveAndLoadPerCompany() {
        try (Db db = TestDb.open(dir)) {
            Service root = new Service(db);
            int companyId = root.listCompanies().get(0).id();
            Service svc = root.forCompany(companyId);

            Map<String, Object> empty = svc.httpClientCollection();
            assertEquals(0L, ((Number) empty.get("revision")).longValue());

            String blob = "{\"name\":\"API\",\"activeId\":\"r1\",\"items\":[{\"id\":\"r1\",\"method\":\"GET\",\"url\":\"https://x.test\"}]}";
            Map<String, Object> saved = svc.saveHttpClientCollection(blob);
            assertTrue(((Number) saved.get("revision")).longValue() > 0);
            assertEquals("API", saved.get("name"));

            Map<String, Object> loaded = svc.httpClientCollection();
            assertEquals("API", loaded.get("name"));
            assertEquals(saved.get("revision"), loaded.get("revision"));
        }
    }
}
