package ponto.model;

import ponto.Problema;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * PostgreSQL bootstrap: resolves the JDBC URL, applies the session timezone,
 * runs the migrations, holds the single connection and stores the tiny config
 * table (worker name).
 */
public final class Db implements AutoCloseable {

    private final Connection c;
    final Object lock;
    private final Integer scopedCompany;
    public final String url;
    public final Path dataDir;
    boolean fresh;

    private Db(Path dir, String jdbcUrl) throws SQLException {
        dataDir = dir;
        dbDir();
        lock = new Object();
        scopedCompany = null;
        url = jdbcUrl;
        c = DriverManager.getConnection(jdbcUrl);
        // All `crea`/`upd` timestamps are stored as text in America/Sao_Paulo
        // local time (the old SQLite schema used strftime('now','localtime'));
        // the session timezone keeps to_char(now(), ...) on the same clock.
        try (Statement st = c.createStatement()) { st.execute("SET TIME ZONE '" + tz() + "'"); }
        try (Statement st = c.createStatement()) { st.execute("SET statement_timeout = 60000"); }
        boolean wasEmpty = !hasTable("cfg");
        try { Migrations.apply(this); }
        catch (RuntimeException e) { close(); throw e; }
        fresh = wasEmpty;
    }

    private void dbDir() { dataDir.toFile().mkdirs(); }

    /** The database URL: `--db=...` wins over $PONTO_DB, which wins over localhost. */
    static String resolve(String cliArg) {
        String raw = nz(cliArg, System.getenv("PONTO_DB"), "jdbc:postgresql://127.0.0.1:5432/ponto");
        if (raw.startsWith("jdbc:postgresql://")) return raw;
        // Convenience: a bare postgresql://host/db or host:port/db still works on the CLI.
        if (raw.startsWith("postgresql://")) return "jdbc:" + raw;
        return "jdbc:postgresql://" + raw;
    }

    /** Directory for auxiliary files (upload staging) — $PONTO_DATA_DIR, else ~/.local/share/ponto. */
    static Path dataDir() {
        String raw = nz(System.getenv("PONTO_DATA_DIR"),
                        System.getProperty("user.home") + "/.local/share/ponto");
        return Path.of(raw).toAbsolutePath();
    }

    static String tz() {
        String env = System.getenv("TZ");
        if (env != null && !env.isBlank()) return env.trim();
        return java.util.TimeZone.getDefault().getID();
    }

    public static Db open(String cliArg) {
        return open(dataDir(), cliArg);
    }

    public static Db open(Path dir, String cliArg) {
        try {
            return new Db(dir.toAbsolutePath(), resolve(cliArg));
        } catch (SQLException e) {
            throw new Problema("postgres: " + clean(e.getMessage()));
        }
    }

    private Db(Db source, int companyId) {
        c = source.c;
        url = source.url;
        dataDir = source.dataDir;
        fresh = source.fresh;
        lock = source.lock;
        scopedCompany = companyId;
    }

    Db forCompany(int id) { return new Db(this, id); }
    boolean scoped() { return scopedCompany != null; }

    /** The id stored in cfg `company`. Always set after {@link Migrations#apply}. */
    public int company() {
        if (scopedCompany != null) return scopedCompany;
        String v = cfg("company");
        if (v.isEmpty()) throw new Problema("sem empresa");
        try { return Integer.parseInt(v); }
        catch (NumberFormatException e) { throw new Problema("empresa: " + v); }
    }

    /** Nested operations join their caller's transaction; all callers share this lock. */
    public <T> T transaction(java.util.function.Supplier<T> work) {
        synchronized (lock) {
            boolean owner = false;
            try {
                owner = c.getAutoCommit();
                if (owner) c.setAutoCommit(false);
                T result = work.get();
                if (owner) c.commit();
                return result;
            } catch (RuntimeException | SQLException | Error e) {
                if (owner) try { c.rollback(); } catch (SQLException suppressed) { e.addSuppressed(suppressed); }
                if (e instanceof RuntimeException runtime) throw runtime;
                if (e instanceof Error error) throw error;
                throw new Problema("transação: " + clean(e.getMessage()));
            } finally {
                if (owner) try { c.setAutoCommit(true); }
                catch (SQLException e) { throw new Problema("transação: " + clean(e.getMessage())); }
            }
        }
    }

    /**
     * Consistent snapshot via pg_dump (custom format). Postgres keeps no
     * on-disk data file, so a file copy is not an option here.
     */
    public void backup(Path target) {
        synchronized (lock) {
            try {
                Path abs = target.toAbsolutePath();
                if (abs.getParent() != null) abs.getParent().toFile().mkdirs();
                var out = ProcessBuilder.Redirect.to(abs.toFile());
                String pgUrl = libpqUrl(url);
                String schema = currentSchema(url);
                var cmd = new java.util.ArrayList<>(java.util.List.of(
                        "pg_dump", "--dbname=" + pgUrl, "--format=custom", "--no-password"));
                if (!schema.isEmpty()) { cmd.add("--schema=" + schema); }
                int code = -1;
                try {
                    code = new ProcessBuilder(cmd).redirectOutput(out).start().waitFor();
                } catch (java.io.IOException missing) {
                    String container = System.getenv("PONTO_PG_CONTAINER");
                    if (container != null && !container.isBlank()) {
                        String inContainer = pgUrl.replaceAll("@(127\\.0\\.0\\.1|localhost):\\d+/", "@127.0.0.1:5432/");
                        var dcmd = new java.util.ArrayList<>(java.util.List.of(
                                "docker", "exec", container,
                                "pg_dump", "--dbname=" + inContainer, "--format=custom", "--no-password"));
                        if (!schema.isEmpty()) { dcmd.add("--schema=" + schema); }
                        code = new ProcessBuilder(dcmd).redirectOutput(out).start().waitFor();
                    } else throw missing;
                }
                if (code != 0) throw new Problema("backup: pg_dump falhou (código " + code + ")");
            } catch (Problema e) { throw e; }
            catch (Exception e) { throw new Problema("backup: " + e.getMessage()); }
        }
    }

    /** Convert `jdbc:postgresql://host:port/db?user=u&password=p` to a libpq URI. */
    public static String libpqUrl(String jdbcUrl) {
        String raw = jdbcUrl.startsWith("jdbc:") ? jdbcUrl.substring(5) : jdbcUrl;
        int q = raw.indexOf('?');
        if (q < 0) return raw;
        String base = raw.substring(0, q);
        String user = "", pass = "";
        StringBuilder extra = new StringBuilder();
        for (String pair : raw.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            if (k.equals("user")) user = v;
            else if (k.equals("password")) pass = v;
            else if (k.equals("currentSchema")) { /* handled via --schema */ }
            else extra.append(extra.isEmpty() ? '?' : '&').append(pair);
        }
        if (!user.isEmpty()) {
            int sl = base.indexOf("://") + 3;
            String creds = user + (pass.isEmpty() ? "" : ":" + pass) + "@";
            base = base.substring(0, sl) + creds + base.substring(sl);
        }
        return base + extra;
    }

    public static String currentSchema(String jdbcUrl) {
        int q = jdbcUrl.indexOf('?');
        if (q < 0) return "";
        for (String pair : jdbcUrl.substring(q + 1).split("&")) {
            if (pair.startsWith("currentSchema=")) return pair.substring("currentSchema=".length());
        }
        return "";
    }

    public boolean healthy() {
        synchronized (lock) {
            try (var q = c.createStatement(); var rs = q.executeQuery("SELECT 1")) { return rs.next(); }
            catch (SQLException e) { return false; }
        }
    }

    /** One-off maintenance pass on the database. */
    public void vacuum() { exec("ANALYZE"); }

    Connection conn() { return c; }

    public String cfg(String k) { return cfg(k, ""); }

    public String cfg(String k, String def) {
        try (var q = c.prepareStatement("SELECT v FROM cfg WHERE k = ?")) {
            q.setString(1, k);
            try (var rs = q.executeQuery()) { return rs.next() ? rs.getString(1) : def; }
        } catch (SQLException e) { throw new Problema("configuração: " + clean(e.getMessage())); }
    }

    public void setCfg(String k, String v) {
        try (var q = c.prepareStatement("INSERT INTO cfg(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v")) {
            q.setString(1, k);
            q.setString(2, v);
            q.executeUpdate();
        } catch (SQLException e) { throw new Problema("configuração: " + clean(e.getMessage())); }
    }

    void exec(String sql) {
        try (Statement st = c.createStatement()) { st.execute(sql); }
        catch (SQLException e) { throw new Problema(clean(e.getMessage())); }
    }

    /** The Postgres server actually in use. */
    public String version() {
        try (Statement st = c.createStatement()) {
            try (var rs = st.executeQuery("SELECT version()")) { return rs.next() ? rs.getString(1) : "?"; }
        } catch (SQLException e) { return "?"; }
    }

    boolean hasTable(String table) {
        try (var q = c.prepareStatement("SELECT 1 FROM information_schema.tables "
                + "WHERE table_schema = current_schema() AND table_name = ?")) {
            q.setString(1, table);
            try (var rs = q.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { throw new Problema(clean(e.getMessage())); }
    }

    boolean hasColumn(String table, String col) {
        try (var q = c.prepareStatement("SELECT 1 FROM information_schema.columns "
                + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?")) {
            q.setString(1, table);
            q.setString(2, col);
            try (var rs = q.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { throw new Problema(clean(e.getMessage())); }
    }

    int migrationVersion() {
        try (var q = c.createStatement(); var rs = q.executeQuery("SELECT version FROM schema_migrations LIMIT 1")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) { throw new Problema(clean(e.getMessage())); }
    }

    @Override public void close() {
        if (scoped()) return;
        try { c.close(); } catch (SQLException ignored) { /* already gone */ }
    }

    private static String nz(String... v) { for (String s : v) if (s != null && !s.isBlank()) return s; return ""; }

    /** Strip the JDBC driver's exception prefix noise. */
    static String clean(String s) {
        if (s == null) return "unknown error";
        int i = s.indexOf(": ");
        return i < 0 ? s : s.substring(i + 2);
    }
}
