package ponto.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class HttpExecutorTest {

    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String method = exchange.getRequestMethod();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String payload = method + "|" + (auth == null ? "" : auth) + "|" + new String(body, StandardCharsets.UTF_8);
            byte[] out = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.getResponseHeaders().add("X-Test", "ok");
            exchange.sendResponseHeaders(201, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.createContext("/bin", exchange -> {
            byte[] out = new byte[] { 0, 1, 2, 3, 4 };
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void postsAndReturnsHeaders() {
        var ex = new HttpExecutor();
        var res = ex.send(new HttpExecutor.Request(
                "POST",
                base + "/echo",
                List.of(new HttpExecutor.Header("Authorization", "Bearer x")),
                "{\"n\":1}",
                true,
                10
        ));
        assertNull(res.error());
        assertEquals(201, res.status());
        assertTrue(res.elapsedMs() >= 0);
        assertEquals("POST|Bearer x|{\"n\":1}", res.body());
        assertFalse(res.bodyBase64());
        assertTrue(res.headers().stream().anyMatch(h -> h.name().equalsIgnoreCase("X-Test") && h.value().equals("ok")));
    }

    @Test
    void binaryAsBase64() {
        var res = new HttpExecutor().send(new HttpExecutor.Request(
                "GET", base + "/bin", List.of(), "", true, 10));
        assertNull(res.error());
        assertEquals(200, res.status());
        assertTrue(res.bodyBase64());
        assertEquals("AAECAwQ=", res.body());
    }

    @Test
    void badUrl() {
        var res = new HttpExecutor().send(new HttpExecutor.Request(
                "GET", "not a url", List.of(), "", true, 5));
        assertNotNull(res.error());
        assertEquals(0, res.status());
    }
}
