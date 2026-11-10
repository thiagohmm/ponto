package ponto.model;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class AssistTest {

    @Test void parseTagIdsKeepsOnlyAllowed() {
        Set<Integer> allowed = Set.of(1, 2, 9);
        assertEquals(List.of(1, 9), Assist.parseTagIds("{\"tagIds\":[1,9,3]}", allowed));
        assertEquals(List.of(2), Assist.parseTagIds("```json\n{\"tagIds\":[2]}\n```", allowed));
        assertEquals(List.of(), Assist.parseTagIds("{\"tagIds\":[3,4]}", allowed));
        assertEquals(List.of(), Assist.parseTagIds("not json", allowed));
        assertEquals(List.of(), Assist.parseTagIds(null, allowed));
    }

    @Test void parseJustUsesTaxonomyAndFallback() {
        var ok = Assist.parseJust("{\"category\":\"atestado\",\"phrases\":[\"Atestado médico\"]}", "x");
        assertEquals("atestado", ok.category());
        assertEquals(List.of("Atestado médico"), ok.phrases());

        var bad = Assist.parseJust("{\"category\":\"inventado\",\"phrases\":[]}", "consulta médica");
        assertEquals("atestado", bad.category());
        assertFalse(bad.phrases().isEmpty());

        var empty = Assist.parseJust("", "home office amanhã");
        assertEquals("home_office", empty.category());
    }

    @Test void guessCategoryFromSynonyms() {
        assertEquals(JustCategory.REUNIAO, Assist.guessCategory("reunião de alinhamento"));
        assertEquals(JustCategory.CLIENTE, Assist.guessCategory("visita ao cliente"));
        assertEquals(JustCategory.OUTRO, Assist.guessCategory("xyz"));
    }

    @Test void disabledAssistNeverAvailable() {
        Assist a = new Assist(URI.create("http://127.0.0.1:9"), "qwen2.5:0.5b", false);
        assertFalse(a.probe());
        assertEquals(false, a.status().get("available"));
        assertEquals(List.of(), a.suggestNoteTags("t", "b", List.of(new Assist.TagRef(1, "x"))));
        assertEquals("outro", a.suggestJust("atestado").category());
    }

    @Test void mockOllamaSuggestsAndStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", ex -> {
            byte[] body = "{\"models\":[{\"name\":\"qwen2.5:0.5b\"}]}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/api/generate", ex -> {
            byte[] req = ex.getRequestBody().readAllBytes();
            String prompt = new String(req, StandardCharsets.UTF_8);
            String response = prompt.contains("tagIds") || prompt.contains("note tags") || prompt.contains("Pick note")
                    || prompt.contains("You pick")
                    ? "{\"tagIds\":[2,99]}"
                    : "{\"category\":\"reuniao\",\"phrases\":[\"Reunião interna\"]}";
            // Ollama wraps model output in {"response":"..."}
            String payload = "{\"response\":" + quote(response) + "}";
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            Assist a = new Assist(base, "qwen2.5:0.5b", true);
            assertTrue(a.probe());
            assertEquals(true, a.status().get("available"));

            List<Integer> ids = a.suggestNoteTags("agenda", "call com time",
                    List.of(new Assist.TagRef(1, "urgente"), new Assist.TagRef(2, "reunião")));
            assertEquals(List.of(2), ids); // 99 rejected

            Assist.JustSuggestion j = a.suggestJust("alinhamento do sprint");
            assertEquals("reuniao", j.category());
            assertEquals(List.of("Reunião interna"), j.phrases());
        } finally {
            server.stop(0);
        }
    }

    @Test void badOllamaUrlDegrades() {
        Assist a = new Assist(URI.create("http://127.0.0.1:1"), "qwen2.5:0.5b", true);
        assertFalse(a.probe());
        assertEquals(List.of(), a.suggestNoteTags("t", "b", List.of(new Assist.TagRef(1, "x"))));
        // heuristic fallback still classifies without the model
        assertEquals("atestado", a.suggestJust("atestado médico").category());
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
