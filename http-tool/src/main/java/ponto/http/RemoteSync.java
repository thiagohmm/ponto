package ponto.http;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Syncs the local collection with the Ponto server DB
 * ({@code GET/PUT /api/http-client/collection}).
 */
public final class RemoteSync {

    public static final String DEFAULT_URL = "https://ponto-thiagohmm1.us1.pitunnel.net";

    public record Config(String url, String user, String password, int companyId, boolean enabled) {
        public Config normalized() {
            String u = url == null || url.isBlank() ? DEFAULT_URL : url.trim();
            while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            return new Config(u, user == null ? "" : user.trim(),
                    password == null ? "" : password,
                    companyId <= 0 ? 1 : companyId,
                    enabled);
        }
    }

    private final Path configFile;
    private final tools.jackson.databind.json.JsonMapper json = new tools.jackson.databind.json.JsonMapper();
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public RemoteSync(Path dataDir) {
        this.configFile = dataDir.resolve("remote.json");
    }

    public Path configPath() { return configFile; }

    public Config loadConfig() {
        try {
            if (!Files.isRegularFile(configFile)) {
                return envConfig().normalized();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = json.readValue(Files.readString(configFile), Map.class);
            String url = str(m.get("url"), DEFAULT_URL);
            String user = str(m.get("user"), env("PONTO_WEB_USER", ""));
            String password = str(m.get("password"), env("PONTO_WEB_PASSWORD", ""));
            int companyId = (int) num(m.get("companyId"), 1);
            boolean enabled = m.get("enabled") == null || Boolean.TRUE.equals(m.get("enabled"))
                    || "true".equalsIgnoreCase(String.valueOf(m.get("enabled")));
            // env overrides when set
            if (!env("PONTO_URL", "").isBlank()) url = env("PONTO_URL", url);
            if (!env("PONTO_WEB_USER", "").isBlank()) user = env("PONTO_WEB_USER", user);
            if (!env("PONTO_WEB_PASSWORD", "").isBlank()) password = env("PONTO_WEB_PASSWORD", password);
            String cid = env("PONTO_COMPANY_ID", "");
            if (!cid.isBlank()) {
                try { companyId = Integer.parseInt(cid.trim()); } catch (NumberFormatException ignored) { }
            }
            return new Config(url, user, password, companyId, enabled).normalized();
        } catch (Exception e) {
            return envConfig().normalized();
        }
    }

    public Config saveConfig(Config cfg) throws Exception {
        Config n = cfg.normalized();
        Files.createDirectories(configFile.getParent());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", n.url());
        m.put("user", n.user());
        m.put("password", n.password());
        m.put("companyId", n.companyId());
        m.put("enabled", n.enabled());
        Files.writeString(configFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(m));
        return n;
    }

    public boolean configured(Config cfg) {
        return cfg != null && cfg.enabled() && !cfg.user().isBlank() && !cfg.password().isBlank();
    }

    public Map<String, Object> pull(Config cfg) throws Exception {
        Config n = cfg.normalized();
        login(n);
        HttpRequest req = HttpRequest.newBuilder(URI.create(n.url() + "/api/http-client/collection?companyId=" + n.companyId()))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() == 401) {
            login(n);
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        if (res.statusCode() >= 400) {
            throw new IllegalStateException("pull HTTP " + res.statusCode() + ": " + truncate(res.body()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = json.readValue(res.body(), Map.class);
        return data == null ? Map.of() : data;
    }

    public Map<String, Object> push(Config cfg, Map<String, Object> collection) throws Exception {
        Config n = cfg.normalized();
        login(n);
        Map<String, Object> body = new LinkedHashMap<>(collection);
        body.remove("revision");
        body.remove("upd");
        String payload = json.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder(URI.create(n.url() + "/api/http-client/collection?companyId=" + n.companyId()))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() == 401) {
            login(n);
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        if (res.statusCode() >= 400) {
            throw new IllegalStateException("push HTTP " + res.statusCode() + ": " + truncate(res.body()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = json.readValue(res.body(), Map.class);
        return data == null ? Map.of() : data;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> companies(Config cfg) throws Exception {
        Config n = cfg.normalized();
        login(n);
        HttpRequest req = HttpRequest.newBuilder(URI.create(n.url() + "/api/companies"))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() >= 400) {
            throw new IllegalStateException("companies HTTP " + res.statusCode() + ": " + truncate(res.body()));
        }
        Map<String, Object> data = json.readValue(res.body(), Map.class);
        Object list = data == null ? null : data.get("companies");
        if (list instanceof List<?> l) return (List<Map<String, Object>>) l;
        return List.of();
    }

    private void login(Config cfg) throws Exception {
        Map<String, String> body = Map.of("user", cfg.user(), "password", cfg.password());
        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.url() + "/api/login"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() >= 400) {
            throw new IllegalStateException("login HTTP " + res.statusCode() + ": " + truncate(res.body()));
        }
    }

    private Config envConfig() {
        String url = env("PONTO_URL", DEFAULT_URL);
        String user = env("PONTO_WEB_USER", "");
        String pass = env("PONTO_WEB_PASSWORD", "");
        int companyId = 1;
        try {
            String cid = env("PONTO_COMPANY_ID", "1");
            companyId = Integer.parseInt(cid.trim());
        } catch (NumberFormatException ignored) { }
        boolean enabled = !user.isBlank() && !pass.isBlank();
        return new Config(url, user, pass, companyId, enabled);
    }

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v.trim();
    }

    private static String str(Object o, String d) {
        return o == null ? d : String.valueOf(o);
    }

    private static long num(Object o, long d) {
        if (o instanceof Number n) return n.longValue();
        if (o == null) return d;
        try { return Long.parseLong(String.valueOf(o)); } catch (NumberFormatException e) { return d; }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
