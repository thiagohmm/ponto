package ponto.http;

import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class HttpRoutes {

    private final HttpExecutor executor = new HttpExecutor();
    private final CollectionStore collection;
    private final RemoteSync remote;
    private final String windowsHost;
    private final KeepScreenOn keepScreenOn;

    HttpRoutes(CollectionStore collection, RemoteSync remote, String windowsHost, KeepScreenOn keepScreenOn) {
        this.collection = collection;
        this.remote = remote;
        this.windowsHost = windowsHost == null || windowsHost.isBlank() ? null : windowsHost;
        this.keepScreenOn = keepScreenOn;
    }

    void register(Javalin app) {
        app.post("/api/send", this::send);
        app.post("/api/import-curl", this::importCurl);
        app.get("/api/collection", this::getCollection);
        app.put("/api/collection", this::putCollection);
        app.get("/api/remote", this::getRemote);
        app.put("/api/remote", this::putRemote);
        app.post("/api/remote/pull", this::pullRemote);
        app.post("/api/remote/push", this::pushRemote);
        app.get("/api/keep-screen-on", this::getKeepScreenOn);
        app.put("/api/keep-screen-on", this::putKeepScreenOn);
        app.get("/api/health", ctx -> ctx.json(Map.of("ok", true)));
    }

    private void getKeepScreenOn(Context ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("supported", keepScreenOn != null && keepScreenOn.isSupported());
        out.put("active", keepScreenOn != null && keepScreenOn.isActive());
        out.put("starting", keepScreenOn != null && keepScreenOn.isStarting());
        String err = keepScreenOn == null ? null : keepScreenOn.lastError();
        if (err != null && !err.isBlank()) {
            out.put("error", err);
        }
        ctx.json(out);
    }

    private void putKeepScreenOn(Context ctx) {
        if (keepScreenOn == null || !keepScreenOn.isSupported()) {
            ctx.status(400).json(Map.of("error", "Recurso disponível apenas no Windows/WSL2."));
            return;
        }
        Map<?, ?> body = ctx.bodyAsClass(Map.class);
        boolean enabled = body != null && Boolean.TRUE.equals(body.get("enabled"));
        if (enabled) {
            keepScreenOn.start();
        } else {
            keepScreenOn.stop();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("supported", true);
        out.put("active", keepScreenOn.isActive());
        String err = keepScreenOn.lastError();
        if (err != null && !err.isBlank()) {
            out.put("error", err);
        }
        ctx.json(out);
    }

    private void getCollection(Context ctx) {
        try {
            ctx.json(collection.load());
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage() == null ? "Falha ao ler collection." : e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private void putCollection(Context ctx) {
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            if (body == null) {
                ctx.status(400).json(Map.of("error", "Corpo inválido."));
                return;
            }
            Map<String, Object> saved = collection.save(body);
            // best-effort push to Ponto DB
            RemoteSync.Config cfg = remote.loadConfig();
            if (remote.configured(cfg)) {
                try {
                    Map<String, Object> pushed = remote.push(cfg, saved);
                    if (pushed.get("revision") != null) {
                        saved.put("remoteRevision", pushed.get("revision"));
                    }
                    saved.put("synced", true);
                } catch (Exception e) {
                    saved.put("synced", false);
                    saved.put("syncError", e.getMessage());
                }
            }
            ctx.json(saved);
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage() == null ? "Falha ao gravar collection." : e.getMessage()));
        }
    }

    private void getRemote(Context ctx) {
        RemoteSync.Config cfg = remote.loadConfig();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", cfg.url());
        out.put("user", cfg.user());
        out.put("password", cfg.password().isEmpty() ? "" : "********");
        out.put("hasPassword", !cfg.password().isEmpty());
        out.put("companyId", cfg.companyId());
        out.put("enabled", cfg.enabled());
        out.put("configured", remote.configured(cfg));
        out.put("configPath", remote.configPath().toString());
        ctx.json(out);
    }

    @SuppressWarnings("unchecked")
    private void putRemote(Context ctx) {
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            if (body == null) {
                ctx.status(400).json(Map.of("error", "Corpo inválido."));
                return;
            }
            RemoteSync.Config cur = remote.loadConfig();
            String url = str(body.get("url"), cur.url());
            String user = str(body.get("user"), cur.user());
            String password = str(body.get("password"), cur.password());
            if ("********".equals(password)) password = cur.password();
            int companyId = body.get("companyId") instanceof Number n ? n.intValue() : cur.companyId();
            boolean enabled = body.get("enabled") == null ? cur.enabled() : Boolean.TRUE.equals(body.get("enabled"));
            RemoteSync.Config saved = remote.saveConfig(new RemoteSync.Config(url, user, password, companyId, enabled));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("url", saved.url());
            out.put("user", saved.user());
            out.put("hasPassword", !saved.password().isEmpty());
            out.put("companyId", saved.companyId());
            out.put("enabled", saved.enabled());
            out.put("configured", remote.configured(saved));
            out.put("ok", true);
            ctx.json(out);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", e.getMessage() == null ? "Falha ao salvar." : e.getMessage()));
        }
    }

    private void pullRemote(Context ctx) {
        try {
            RemoteSync.Config cfg = remote.loadConfig();
            if (!remote.configured(cfg)) {
                ctx.status(400).json(Map.of("error", "Configure URL, usuário e senha do Ponto (aba Sync)."));
                return;
            }
            Map<String, Object> remoteData = remote.pull(cfg);
            Map<String, Object> saved = collection.save(remoteData);
            saved.put("synced", true);
            saved.put("source", "remote");
            ctx.json(saved);
        } catch (Exception e) {
            ctx.status(502).json(Map.of("error", e.getMessage() == null ? "Falha no pull." : e.getMessage()));
        }
    }

    private void pushRemote(Context ctx) {
        try {
            RemoteSync.Config cfg = remote.loadConfig();
            if (!remote.configured(cfg)) {
                ctx.status(400).json(Map.of("error", "Configure URL, usuário e senha do Ponto (aba Sync)."));
                return;
            }
            Map<String, Object> local = collection.load();
            Map<String, Object> pushed = remote.push(cfg, local);
            pushed.put("synced", true);
            ctx.json(pushed);
        } catch (Exception e) {
            ctx.status(502).json(Map.of("error", e.getMessage() == null ? "Falha no push." : e.getMessage()));
        }
    }

    private void send(Context ctx) {
        SendIn in = ctx.bodyAsClass(SendIn.class);
        if (in == null) {
            ctx.status(400).json(Map.of("error", "Corpo inválido."));
            return;
        }
        List<HttpExecutor.Header> headers = new ArrayList<>();
        if (in.headers != null) {
            for (HeaderIn h : in.headers) {
                if (h == null || h.name == null || h.name.isBlank()) continue;
                if (h.enabled != null && !h.enabled) continue;
                headers.add(new HttpExecutor.Header(h.name, h.value == null ? "" : h.value));
            }
        }
        String url = in.url;
        String resolved = url;
        boolean mapped = false;
        if (Boolean.TRUE.equals(in.mapWindowsHost) && windowsHost != null) {
            resolved = Wsl.mapLocalhostToWindows(url, windowsHost);
            mapped = resolved != null && !resolved.equals(url);
        }
        HttpExecutor.Response res = executor.send(new HttpExecutor.Request(
                in.method,
                resolved,
                headers,
                in.body == null ? "" : in.body,
                in.followRedirects == null || in.followRedirects,
                in.timeoutSeconds == null ? 30 : in.timeoutSeconds
        ));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", res.status());
        out.put("statusText", res.statusText());
        out.put("elapsedMs", res.elapsedMs());
        out.put("contentType", res.contentType());
        out.put("body", res.body());
        out.put("bodyBase64", res.bodyBase64());
        out.put("error", res.error());
        out.put("url", resolved);
        if (mapped) out.put("mappedFrom", url);
        List<Map<String, String>> hs = new ArrayList<>();
        for (HttpExecutor.Header h : res.headers()) {
            hs.add(Map.of("name", h.name(), "value", h.value() == null ? "" : h.value()));
        }
        out.put("headers", hs);
        ctx.json(out);
    }

    private void importCurl(Context ctx) {
        Map<?, ?> body = ctx.bodyAsClass(Map.class);
        Object curl = body == null ? null : body.get("curl");
        if (curl == null || curl.toString().isBlank()) {
            ctx.status(400).json(Map.of("error", "Cole um comando curl."));
            return;
        }
        try {
            CurlParser.Draft d = CurlParser.parse(curl.toString());
            List<Map<String, Object>> headers = new ArrayList<>();
            d.headers().forEach((k, v) -> {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("name", k);
                h.put("value", v);
                h.put("enabled", true);
                headers.add(h);
            });
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("method", d.method());
            out.put("url", d.url());
            out.put("headers", headers);
            out.put("body", d.body());
            ctx.json(out);
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        }
    }

    private static String str(Object o, String d) {
        return o == null ? d : String.valueOf(o);
    }

    public static final class SendIn {
        public String method;
        public String url;
        public List<HeaderIn> headers;
        public String body;
        public Boolean followRedirects;
        public Integer timeoutSeconds;
        public Boolean mapWindowsHost;
    }

    public static final class HeaderIn {
        public String name;
        public String value;
        public Boolean enabled;
    }
}
