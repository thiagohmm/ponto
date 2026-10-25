package ponto.model;

import ponto.Problema;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite bootstrap: resolves the db file, applies pragmas + schema, holds the
 * single connection, and stores the tiny config table (worker name).
 */
public final class Db implements AutoCloseable {

    /** One statement per element: the JDBC driver runs only the first one otherwise. */
    private static final java.util.List<String> SCHEMA = java.util.List.of(
        """
        CREATE TABLE IF NOT EXISTS ponto (
          id     INTEGER PRIMARY KEY AUTOINCREMENT,
          dia    TEXT NOT NULL,
          tin    TEXT,
          tout   TEXT,
          just   TEXT NOT NULL DEFAULT '',
          crea   TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M','now','localtime'))
        )""",
        "CREATE INDEX IF NOT EXISTS ix_ponto_dia ON ponto(dia)",
        """
        CREATE TABLE IF NOT EXISTS cfg (
          k      TEXT PRIMARY KEY,
          v      TEXT NOT NULL DEFAULT ''
        )""");

    private final Connection c;
    public final Path file;
    final boolean fresh;

    private Db(Path p) throws SQLException {
        file = p;
        fresh = !p.toFile().exists() || p.toFile().length() == 0;
        var parent = p.toAbsolutePath().getParent();
        if (parent != null) parent.toFile().mkdirs();
        c = DriverManager.getConnection("jdbc:sqlite:" + p.toAbsolutePath());
        for (String s : java.util.List.of("PRAGMA journal_mode = WAL",
                                           "PRAGMA foreign_keys = ON",
                                           "PRAGMA busy_timeout = 5000",
                                           "PRAGMA synchronous = NORMAL")) {
            try (Statement st = c.createStatement()) { st.execute(s); }
        }
        for (String sql : SCHEMA) exec(sql);
    }

    /** CLI --db=... wins over $PONTO_DB, which wins over ~/.local/share/ponto/ponto.db */
    static Path resolve(String cliArg) {
        String home = System.getProperty("user.home");
        String raw = nz(cliArg, System.getenv("PONTO_DB"), home + "/.local/share/ponto/ponto.db");
        Path p = Path.of(raw);
        return p.isAbsolute() ? p : Path.of(System.getProperty("user.dir")).resolve(p);
    }

    public static Db open(String cliArg) {
        try {
            return new Db(resolve(cliArg));
        } catch (SQLException e) {
            throw new Problema("sqlite: " + clean(e.getMessage()));
        }
    }

    /** One-off db cleanup (delete a data file the user no longer wants). */
    public void vacuum() { exec("VACUUM"); }

    Connection conn() { return c; }

    public String cfg(String k) { return cfg(k, ""); }

    public String cfg(String k, String def) {
        try (var q = c.prepareStatement("SELECT v FROM cfg WHERE k = ?")) {
            q.setString(1, k);
            try (var rs = q.executeQuery()) { return rs.next() ? rs.getString(1) : def; }
        } catch (SQLException ignored) { return def; }
    }

    public void setCfg(String k, String v) {
        try (var q = c.prepareStatement("INSERT INTO cfg(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v")) {
            q.setString(1, k);
            q.setString(2, v);
            q.executeUpdate();
        } catch (SQLException e) { throw new Problema("cfg: " + clean(e.getMessage())); }
    }

    void exec(String sql) {
        try (Statement st = c.createStatement()) { st.execute(sql); }
        catch (SQLException e) { throw new Problema(clean(e.getMessage())); }
    }

    /** The SQLite engine actually in use. */
    public String version() {
        try (Statement st = c.createStatement()) {
            try (var rs = st.executeQuery("SELECT sqlite_version()")) { return rs.next() ? rs.getString(1) : "?"; }
        } catch (SQLException e) { return "?"; }
    }

    @Override public void close() {
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
