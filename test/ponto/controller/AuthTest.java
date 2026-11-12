package ponto.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ponto.TestDb;
import ponto.model.Db;
import ponto.model.Service;
import ponto.view.Json3;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AuthTest {
    @TempDir Path dir;

    @Test void loginProtectsApiAndHealthStaysOpen() throws Exception {
        try (Db db = TestDb.open(dir)) {
            var auth = new Auth("alice", "s3cret!");
            var app = new Web(new Service(db), 0, auth).application().start("127.0.0.1", 0);
            String base = "http://127.0.0.1:" + app.port();
            var jar = new CookieManager();
            jar.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
            var client = HttpClient.newBuilder().cookieHandler(jar).build();
            try {
                assertEquals(200, get(client, base + "/api/health").statusCode());
                assertEquals(401, get(client, base + "/api/companies").statusCode());
                assertEquals(401, post(client, base + "/api/login", "{\"user\":\"alice\",\"password\":\"wrong\"}").statusCode());
                var ok = post(client, base + "/api/login", "{\"user\":\"alice\",\"password\":\"s3cret!\"}");
                assertEquals(200, ok.statusCode());
                Map<?,?> login = new Json3().fromJsonString(ok.body(), Map.class);
                assertEquals("alice", login.get("user"));
                assertEquals(200, get(client, base + "/api/me").statusCode());
                assertEquals(200, get(client, base + "/api/companies").statusCode());
                assertEquals(200, post(client, base + "/api/logout", "{}").statusCode());
                assertEquals(401, get(client, base + "/api/companies").statusCode());
            } finally {
                app.stop();
            }
        }
    }

    @Test void slowEqualsRejectsDifferentLengths() {
        assertTrue(Auth.slowEquals("ab".getBytes(), "ab".getBytes()));
        assertFalse(Auth.slowEquals("a".getBytes(), "ab".getBytes()));
        assertFalse(Auth.slowEquals("ab".getBytes(), "ac".getBytes()));
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(HttpClient client, String url, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
