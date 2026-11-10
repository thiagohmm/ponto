package ponto.model;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Optional local Ollama client for soft suggestions (note tags, just categories).
 * Never writes to the DB; never touches BankRules. Failures degrade to empty.
 *
 * Env:
 *   PONTO_ASSIST=0          disable entirely
 *   PONTO_OLLAMA_URL        default http://127.0.0.1:11434
 *   PONTO_ASSIST_MODEL      default qwen2.5:0.5b
 */
public final class Assist {

    public static final String ENV_ON = "PONTO_ASSIST";
    public static final String ENV_URL = "PONTO_OLLAMA_URL";
    public static final String ENV_MODEL = "PONTO_ASSIST_MODEL";
    public static final String DEFAULT_MODEL = "qwen2.5:0.5b";
    public static final String DEFAULT_URL = "http://127.0.0.1:11434";

    private static final Duration CONNECT = Duration.ofMillis(800);
    private static final Duration REQUEST = Duration.ofMillis(2000);
    private static final Pattern FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern INT = Pattern.compile("-?\\d+");

    private final URI base;
    private final String model;
    private final boolean enabled;
    private final HttpClient http;
    private final tools.jackson.databind.json.JsonMapper json = new tools.jackson.databind.json.JsonMapper();

    public record TagRef(int id, String name) { }
    public record JustSuggestion(String category, List<String> phrases) { }

    public Assist(URI base, String model, boolean enabled) {
        this(base, model, enabled, HttpClient.newBuilder().connectTimeout(CONNECT).build());
    }

    /** Test hook with a custom client. */
    public Assist(URI base, String model, boolean enabled, HttpClient http) {
        this.base = base;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        this.enabled = enabled;
        this.http = http;
    }

    public static Assist env() {
        String off = nz(System.getenv(ENV_ON));
        boolean on = !off.equals("0") && !off.equalsIgnoreCase("false") && !off.equalsIgnoreCase("off");
        String url = nz(System.getenv(ENV_URL), DEFAULT_URL);
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        String model = nz(System.getenv(ENV_MODEL), DEFAULT_MODEL);
        try {
            return new Assist(URI.create(url), model, on);
        } catch (IllegalArgumentException e) {
            return new Assist(URI.create(DEFAULT_URL), model, false);
        }
    }

    public String model() { return model; }
    public boolean enabled() { return enabled; }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", model);
        out.put("categories", JustCategory.catalog());
        if (!enabled) {
            out.put("available", false);
            return out;
        }
        out.put("available", probe());
        return out;
    }

    /** True when Ollama answers and the configured model is present (or any model if tags empty). */
    public boolean probe() {
        if (!enabled) return false;
        try {
            HttpRequest req = HttpRequest.newBuilder(base.resolve("/api/tags"))
                    .timeout(REQUEST).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) return false;
            String body = res.body() == null ? "" : res.body();
            String want = model.toLowerCase(Locale.ROOT);
            // accept exact name or name without tag, e.g. qwen2.5:0.5b vs qwen2.5
            if (body.toLowerCase(Locale.ROOT).contains("\"name\":\"" + want + "\"")) return true;
            int colon = want.indexOf(':');
            String baseName = colon > 0 ? want.substring(0, colon) : want;
            return body.toLowerCase(Locale.ROOT).contains("\"name\":\"" + baseName);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Suggest a subset of candidate tag ids for the note. Empty on any failure.
     * Only ids present in {@code tags} are returned.
     */
    public List<Integer> suggestNoteTags(String title, String body, List<TagRef> tags) {
        if (!enabled || tags == null || tags.isEmpty()) return List.of();
        Set<Integer> allowed = new LinkedHashSet<>();
        StringBuilder catalog = new StringBuilder();
        for (TagRef t : tags) {
            if (t == null || t.name() == null || t.name().isBlank()) continue;
            allowed.add(t.id());
            if (!catalog.isEmpty()) catalog.append(", ");
            catalog.append(t.id()).append('=').append(t.name().trim());
        }
        if (allowed.isEmpty()) return List.of();
        String prompt = """
                You pick note tags. Reply with JSON only: {"tagIds":[id,...]}
                Use only ids from this list: %s
                Pick zero or more that fit the note. No other text.
                Title: %s
                Body: %s
                """.formatted(catalog, clip(title, 200), clip(body, 800));
        String raw = generate(prompt);
        return parseTagIds(raw, allowed);
    }

    /**
     * Suggest a justification category + short phrases. Empty/"outro" on failure.
     */
    public JustSuggestion suggestJust(String text) {
        if (!enabled) return new JustSuggestion("outro", List.of());
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return new JustSuggestion("outro", JustCategory.OUTRO.phrases());
        String codes = String.join("|",
                java.util.Arrays.stream(JustCategory.values()).map(JustCategory::code).toList());
        String prompt = """
                Classify a timesheet justification. Reply with JSON only:
                {"category":"CODE","phrases":["short phrase",...]}
                CODE must be one of: %s
                phrases: 1 to 3 short Portuguese suggestions the user may type.
                Text: %s
                """.formatted(codes, clip(t, 400));
        String raw = generate(prompt);
        return parseJust(raw, t);
    }

    /** Package-visible for unit tests without HTTP. */
    static List<Integer> parseTagIds(String raw, Set<Integer> allowed) {
        if (raw == null || raw.isBlank() || allowed == null || allowed.isEmpty()) return List.of();
        String json = extractJson(raw);
        List<Integer> out = new ArrayList<>();
        try {
            var tree = new tools.jackson.databind.json.JsonMapper().readTree(json);
            var arr = tree.get("tagIds");
            if (arr != null && arr.isArray()) {
                for (var n : arr) {
                    int id = n.asInt(Integer.MIN_VALUE);
                    if (allowed.contains(id) && !out.contains(id)) out.add(id);
                }
                return List.copyOf(out);
            }
        } catch (Exception ignored) { }
        // fallback: any integers that are allowed ids
        Matcher m = INT.matcher(json);
        while (m.find()) {
            try {
                int id = Integer.parseInt(m.group());
                if (allowed.contains(id) && !out.contains(id)) out.add(id);
            } catch (NumberFormatException ignored) { }
        }
        return List.copyOf(out);
    }

    static JustSuggestion parseJust(String raw, String original) {
        JustCategory fallback = guessCategory(original);
        if (raw == null || raw.isBlank())
            return new JustSuggestion(fallback.code(), fallback.phrases());
        String json = extractJson(raw);
        try {
            var tree = new tools.jackson.databind.json.JsonMapper().readTree(json);
            String code = tree.path("category").asString("");
            JustCategory cat = JustCategory.ofCode(code).orElse(fallback);
            List<String> phrases = new ArrayList<>();
            var arr = tree.get("phrases");
            if (arr != null && arr.isArray()) {
                for (var n : arr) {
                    String p = n.asString("").trim();
                    if (!p.isEmpty() && phrases.size() < 3) phrases.add(p);
                }
            }
            if (phrases.isEmpty()) phrases = new ArrayList<>(cat.phrases());
            return new JustSuggestion(cat.code(), List.copyOf(phrases));
        } catch (Exception e) {
            return new JustSuggestion(fallback.code(), fallback.phrases());
        }
    }

    static JustCategory guessCategory(String text) {
        String n = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (JustCategory c : JustCategory.values()) {
            if (c == JustCategory.OUTRO) continue;
            for (String s : c.synonyms()) {
                if (!s.isEmpty() && n.contains(s.toLowerCase(Locale.ROOT))) return c;
            }
        }
        return JustCategory.OUTRO;
    }

    static String extractJson(String raw) {
        String s = raw.trim();
        Matcher fence = FENCE.matcher(s);
        if (fence.find()) s = fence.group(1).trim();
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a >= 0 && b > a) return s.substring(a, b + 1);
        return s;
    }

    private String generate(String prompt) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("stream", false);
            body.put("prompt", prompt);
            body.put("format", "json");
            body.put("options", Map.of("temperature", 0, "num_predict", 128));
            String payload = json.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder(base.resolve("/api/generate"))
                    .timeout(REQUEST)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200 || res.body() == null) return "";
            var tree = json.readTree(res.body());
            return tree.path("response").asString("");
        } catch (Exception e) {
            return "";
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.trim().replace('\r', ' ').replace('\n', ' ');
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String nz(String s) { return s == null ? "" : s.trim(); }
    private static String nz(String s, String d) {
        String v = nz(s);
        return v.isEmpty() ? d : v;
    }
}
