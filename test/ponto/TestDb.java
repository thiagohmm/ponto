package ponto;

import ponto.model.Db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Isolated PostgreSQL schemas for JUnit and CLI tests.
 *
 * Uses `$PONTO_TEST_PG` when set (e.g. CI service container), otherwise connects to
 * `127.0.0.1:55432/ponto_test` and boots a disposable `ponto-test-pg` Docker container
 * (`postgres:16-alpine` on tmpfs) on demand when needed.
 */
public final class TestDb {

    private static final String DEFAULT_CONTAINER = "ponto-test-pg";
    private static final String DEFAULT_URL =
            "jdbc:postgresql://127.0.0.1:55432/ponto_test?user=ponto&password=ponto";

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static volatile String resolvedBase;

    private TestDb() {}

    public static String baseUrl() {
        String b = resolvedBase;
        if (b != null) return b;
        synchronized (TestDb.class) {
            if (resolvedBase != null) return resolvedBase;
            String env = System.getenv("PONTO_TEST_PG");
            if (env != null && !env.isBlank()) {
                resolvedBase = env.startsWith("jdbc:") ? env : "jdbc:" + env;
                waitReady(resolvedBase, 15);
                return resolvedBase;
            }
            if (!canConnect(DEFAULT_URL)) startDockerPostgres();
            waitReady(DEFAULT_URL, 25);
            resolvedBase = DEFAULT_URL;
            return resolvedBase;
        }
    }

    /** Allocate a brand-new empty schema and return its JDBC URL. */
    public static String freshUrl() {
        String base = baseUrl();
        String schema = "t_" + ProcessHandle.current().pid() + "_" + SEQ.incrementAndGet();
        try (Connection c = DriverManager.getConnection(base);
             Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            st.execute("CREATE SCHEMA " + schema);
        } catch (SQLException e) {
            throw new RuntimeException("TestDb schema setup failed: " + e.getMessage(), e);
        }
        return withSchema(base, schema);
    }

    public static Db open() {
        try {
            Path dir = Files.createTempDirectory("ponto-test-");
            return Db.open(dir, freshUrl());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Db open(Path dataDir) {
        return Db.open(dataDir, freshUrl());
    }

    public static String withSchema(String base, String schema) {
        char sep = base.contains("?") ? '&' : '?';
        return base + sep + "currentSchema=" + schema;
    }

    /** Drops the schema pointed to by the URL's currentSchema and restores <dump> into it. */
    public static void restoreDump(String jdbcUrl, Path dump) throws Exception {
        String schema = schemaOf(jdbcUrl);
        // Restore is all-or-nothing: drop the target schema first so no stale tables survive.
        try (var c = DriverManager.getConnection(jdbcUrl);
             var q = c.createStatement()) {
            if (!schema.isEmpty()) q.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
        }
        String pgUrl = Db.libpqUrl(jdbcUrl);
        int code = new ProcessBuilder("pg_restore", "--dbname=" + pgUrl, "--no-owner", "--no-privileges",
                dump.toString()).redirectErrorStream(true).start().waitFor();
        if (code != 0) {
            // No pg_restore on the host: copy the dump into the container and restore from there
            // (port 5432 is PostgreSQL's own port inside the container).
            String inner = pgUrl.replaceAll("@(127\\.0\\.0\\.1|localhost):\\d+/", "@127.0.0.1:5432/");
            new ProcessBuilder("docker", "cp", dump.toString(), containerName() + ":/tmp/ponto-restore.dump")
                    .redirectErrorStream(true).start().waitFor();
            code = new ProcessBuilder("docker", "exec", containerName(),
                    "pg_restore", "--dbname=" + inner, "--no-owner", "--no-privileges",
                    "/tmp/ponto-restore.dump").redirectErrorStream(true).start().waitFor();
        }
        if (code != 0) throw new IllegalStateException("pg_restore failed");
    }

    public static String schemaOf(String url) {
        int i = url.indexOf("currentSchema=");
        if (i < 0) return "public";
        String tail = url.substring(i + "currentSchema=".length());
        int amp = tail.indexOf('&');
        return amp < 0 ? tail : tail.substring(0, amp);
    }

    public static String containerName() {
        String env = System.getenv("PONTO_PG_CONTAINER");
        return (env != null && !env.isBlank()) ? env : DEFAULT_CONTAINER;
    }

    private static boolean canConnect(String url) {
        try (Connection c = DriverManager.getConnection(url)) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static void waitReady(String url, int seconds) {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        SQLException last = null;
        while (System.currentTimeMillis() < deadline) {
            try (Connection c = DriverManager.getConnection(url)) {
                return;
            } catch (SQLException e) {
                last = e;
                try { Thread.sleep(250); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        throw new RuntimeException("Postgres de teste indisponível em " + url
                + (last != null ? ": " + last.getMessage() : ""));
    }

    private static void startDockerPostgres() {
        try {
            new ProcessBuilder("docker", "rm", "-f", DEFAULT_CONTAINER)
                    .redirectErrorStream(true).start().waitFor();
            int code = new ProcessBuilder(
                    "docker", "run", "-d",
                    "--name", DEFAULT_CONTAINER,
                    "-p", "127.0.0.1:55432:5432",
                    "--tmpfs", "/var/lib/postgresql/data",
                    "-e", "POSTGRES_USER=ponto",
                    "-e", "POSTGRES_PASSWORD=ponto",
                    "-e", "POSTGRES_DB=ponto_test",
                    "-e", "TZ=America/Sao_Paulo",
                    "postgres:16-alpine")
                    .redirectErrorStream(true).start().waitFor();
            if (code != 0) throw new RuntimeException("docker run postgres:16-alpine retornou " + code);
        } catch (Exception e) {
            throw new RuntimeException("Não foi possível subir Postgres de teste via Docker "
                    + "(defina PONTO_TEST_PG ou inicie o Docker): " + e.getMessage(), e);
        }
    }

    /** CLI entry point for shell scripts (`smoke.sh`): prints a fresh schema URL. */
    public static void main(String[] args) {
        System.out.println(freshUrl());
    }
}
