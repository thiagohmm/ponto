package ponto.http;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Local HTTP client. Binds only to 127.0.0.1 so requests leave from this machine.
 *
 *   java -jar ponto-http.jar
 *   java -jar ponto-http.jar --port=1984
 *   java -jar ponto-http.jar --no-open
 *
 * Requires Java 17 or newer (through Java 25+). Works on WSL2 (opens the Windows
 * browser and can map localhost → Windows host for destination APIs).
 */
public final class HttpTool {

    public static final int DEFAULT_PORT = 1984;

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        boolean openBrowser = true;
        for (String a : args) {
            if (a.startsWith("--port=")) {
                try {
                    port = Integer.parseInt(a.substring("--port=".length()).trim());
                } catch (NumberFormatException e) {
                    System.err.println("Porta inválida: " + a);
                    System.exit(2);
                    return;
                }
            } else if (a.equals("--no-open")) {
                openBrowser = false;
            } else if (a.equals("--help") || a.equals("-h")) {
                System.out.println("""
                        ponto-http — cliente HTTP local (Java 17+)

                        usage
                          java -jar ponto-http.jar
                          java -jar ponto-http.jar --port=1984
                          java -jar ponto-http.jar --no-open

                        Abra http://127.0.0.1:<porta> no navegador.
                        No WSL2, o browser do Windows é aberto automaticamente e
                        localhost nas URLs de destino pode ser mapeado para o host Windows.
                        """);
                return;
            }
        }
        if (port < 1 || port > 65535) {
            System.err.println("Porta fora do intervalo 1–65535.");
            System.exit(2);
            return;
        }

        String windowsHost = Wsl.windowsHostIp();
        CollectionStore collection = CollectionStore.defaultStore();
        RemoteSync remote = new RemoteSync(collection.path().getParent());
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("wsl", Wsl.isWsl());
        env.put("windowsHost", windowsHost == null ? "" : windowsHost);
        env.put("dataPath", collection.path().toString());
        env.put("defaultRemoteUrl", RemoteSync.DEFAULT_URL);

        Javalin app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.jsonMapper(new JsonMap());
            cfg.jetty.modifyHttpConfiguration(http -> http.setSendServerVersion(false));
            cfg.staticFiles.add(s -> {
                s.hostedPath = "/";
                s.directory = "/http-ui";
                s.location = Location.CLASSPATH;
                s.headers = Map.of("Cache-Control", "no-store");
            });
        });

        new HttpRoutes(collection, remote, windowsHost).register(app);
        app.get("/api/env", ctx -> ctx.json(env));

        // Pull shared collection from Ponto DB on startup when credentials exist.
        RemoteSync.Config remoteCfg = remote.loadConfig();
        if (remote.configured(remoteCfg)) {
            try {
                Map<String, Object> pulled = remote.pull(remoteCfg);
                collection.save(pulled);
                System.out.println("Collection baixada de " + remoteCfg.url());
            } catch (Exception e) {
                System.err.println("Aviso: não foi possível baixar a collection remota: " + e.getMessage());
            }
        }

        app.exception(IllegalArgumentException.class, (e, ctx) ->
                ctx.status(400).json(Map.of("error", e.getMessage() == null ? "Requisição inválida." : e.getMessage())));
        app.exception(Exception.class, (e, ctx) ->
                ctx.status(500).json(Map.of("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));

        // Bind loopback only — never expose on the LAN by default.
        // WSL2 localhost forwarding makes Windows browsers reach this address.
        try {
            app.start("127.0.0.1", port);
        } catch (Exception e) {
            System.err.println("Não foi possível abrir a porta " + port + ": " + e.getMessage());
            System.err.println("Tente outra porta: java -jar ponto-http.jar --port=1985");
            System.exit(1);
            return;
        }

        String url = "http://127.0.0.1:" + port;
        System.out.println("ponto-http pronto em " + url);
        System.out.println("Cache local: " + collection.path());
        System.out.println("Fonte remota (DB): " + remoteCfg.url() + " · sync "
                + (remote.configured(remoteCfg) ? "ligado" : "desligado (configure na UI)"));
        if (Wsl.isWsl()) {
            System.out.println("WSL detectado — use o browser do Windows em " + url);
            if (windowsHost != null) {
                System.out.println("Host Windows (para APIs em localhost no Windows): " + windowsHost);
            }
        }
        System.out.println("Ctrl+C para sair.");

        if (openBrowser) {
            if (Wsl.isWsl()) {
                if (!Wsl.openWindowsBrowser(url)) {
                    System.out.println("Abra manualmente: " + url);
                }
            }
        }
    }

    private HttpTool() { }
}
