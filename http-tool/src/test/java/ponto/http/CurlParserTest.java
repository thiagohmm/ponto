package ponto.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CurlParserTest {

    @Test
    void simpleGet() {
        var d = CurlParser.parse("curl https://example.com/api");
        assertEquals("GET", d.method());
        assertEquals("https://example.com/api", d.url());
        assertTrue(d.headers().isEmpty());
        assertEquals("", d.body());
    }

    @Test
    void postWithHeadersAndData() {
        String curl = """
                curl -X POST 'https://api.example.com/v1' \\
                  -H 'Authorization: Bearer tok' \\
                  -H 'Content-Type: application/json' \\
                  -d '{"a":1}'
                """;
        var d = CurlParser.parse(curl);
        assertEquals("POST", d.method());
        assertEquals("https://api.example.com/v1", d.url());
        assertEquals("Bearer tok", d.headers().get("Authorization"));
        assertEquals("application/json", d.headers().get("Content-Type"));
        assertEquals("{\"a\":1}", d.body());
    }

    @Test
    void dataImpliesPost() {
        var d = CurlParser.parse("curl https://x.test --data 'a=1&b=2'");
        assertEquals("POST", d.method());
        assertEquals("a=1&b=2", d.body());
        assertEquals("application/x-www-form-urlencoded", d.headers().get("Content-Type"));
    }

    @Test
    void basicAuth() {
        var d = CurlParser.parse("curl -u user:secret https://x.test/p");
        assertEquals("https://x.test/p", d.url());
        assertTrue(d.headers().get("Authorization").startsWith("Basic "));
    }

    @Test
    void multilineAndQuotes() {
        var d = CurlParser.parse("curl --request PUT --url \"https://x.test/item\" -H \"X-A: 1\"");
        assertEquals("PUT", d.method());
        assertEquals("https://x.test/item", d.url());
        assertEquals("1", d.headers().get("X-A"));
    }

    @Test
    void rejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> CurlParser.parse("  "));
    }

    @Test
    void rejectsMissingUrl() {
        assertThrows(IllegalArgumentException.class, () -> CurlParser.parse("curl -X GET"));
    }
}
