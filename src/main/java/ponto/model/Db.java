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
        CREATE TABLE IF NOT EXISTS dia_flag (
          dia    TEXT PRIMARY KEY,
          flag   TEXT NOT NULL DEFAULT 'falta'
        )""",
        """
        CREATE TABLE IF NOT EXISTS cfg (
          k      TEXT PRIMARY KEY,
          v      TEXT NOT NULL DEFAULT ''
        )""",
        """
        CREATE TABLE IF NOT EXISTS note (
          id        INTEGER PRIMARY KEY AUTOINCREMENT,
          title     TEXT NOT NULL DEFAULT '',
          body      TEXT NOT NULL DEFAULT '',
          crea      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M','now','localtime')),
          upd       TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M','now','localtime')),
          folder_id INTEGER
        )""",
        "CREATE INDEX IF NOT EXISTS ix_note_upd ON note(upd)",
        """
        CREATE TABLE IF NOT EXISTS folder (
          id     INTEGER PRIMARY KEY AUTOINCREMENT,
          parent INTEGER,
          name   TEXT NOT NULL DEFAULT '',
          crea   TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M','now','localtime'))
        )""",
        "CREATE INDEX IF NOT EXISTS ix_folder_parent ON folder(parent)",
        """
        CREATE TABLE IF NOT EXISTS tag (
          id     INTEGER PRIMARY KEY AUTOINCREMENT,
          name   TEXT NOT NULL DEFAULT '',
          color  TEXT NOT NULL DEFAULT '',
          crea   TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M','now','localtime'))
        )""",
        """
        CREATE TABLE IF NOT EXISTS note_tag (
          note_id INTEGER NOT NULL,
          tag_id  INTEGER NOT NULL,
          PRIMARY KEY (note_id, tag_id)
        )""",
        "CREATE INDEX IF NOT EXISTS ix_note_tag_tag ON note_tag(tag_id)",
        """
        CREATE TABLE IF NOT EXISTS note_file (
          id      INTEGER PRIMARY KEY AUTOINCREMENT,
          note_id INTEGER NOT NULL,
          name    TEXT NOT NULL DEFAULT '',
          key     TEXT NOT NULL,
          size    INTEGER NOT NULL DEFAULT 0,
          ctype   TEXT NOT NULL DEFAULT '',
          crea    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M','now','localtime'))
        )""",
        "CREATE INDEX IF NOT EXISTS ix_note_file_note ON note_file(note_id)",
        """
        CREATE TABLE IF NOT EXISTS company (
          id     INTEGER PRIMARY KEY AUTOINCREMENT,
          name   TEXT NOT NULL DEFAULT '',
          off    TEXT NOT NULL DEFAULT '',
          daily  TEXT NOT NULL DEFAULT '',
          crea   TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M','now','localtime'))
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
        if (!hasColumn("note", "folder_id"))
            exec("ALTER TABLE note ADD COLUMN folder_id INTEGER");
        exec("CREATE INDEX IF NOT EXISTS ix_note_folder ON note(folder_id)");
        migrateCompanies();
    }

    /**
     * One active company per database file. Existing rows (and the old cfg
     * off/daily) land on a seeded company so a production db keeps its data.
     */
    private void migrateCompanies() {
        addColumn("ponto", "company_id");
        addColumn("note", "company_id");
        addColumn("folder", "company_id");
        addColumn("tag", "company_id");
        int id = seedCompany();
        exec("UPDATE ponto SET company_id = " + id + " WHERE company_id IS NULL");
        exec("UPDATE note SET company_id = " + id + " WHERE company_id IS NULL");
        exec("UPDATE folder SET company_id = " + id + " WHERE company_id IS NULL");
        exec("UPDATE tag SET company_id = " + id + " WHERE company_id IS NULL");
        exec("CREATE INDEX IF NOT EXISTS ix_ponto_company ON ponto(company_id, dia)");
        exec("CREATE INDEX IF NOT EXISTS ix_note_company ON note(company_id)");
        exec("CREATE INDEX IF NOT EXISTS ix_folder_company ON folder(company_id)");
        exec("CREATE INDEX IF NOT EXISTS ix_tag_company ON tag(company_id)");
        if (!hasColumn("dia_flag", "company_id")) {
            exec("""
                CREATE TABLE dia_flag_new (
                  company_id INTEGER NOT NULL,
                  dia        TEXT NOT NULL,
                  flag       TEXT NOT NULL DEFAULT 'falta',
                  PRIMARY KEY (company_id, dia)
                )""");
            exec("INSERT INTO dia_flag_new(company_id, dia, flag) SELECT "
                    + id + ", dia, flag FROM dia_flag");
            exec("DROP TABLE dia_flag");
            exec("ALTER TABLE dia_flag_new RENAME TO dia_flag");
        }
        if (cfg("company").isEmpty()) setCfg("company", String.valueOf(id));
    }

    private void addColumn(String table, String col) {
        if (!hasColumn(table, col)) exec("ALTER TABLE " + table + " ADD COLUMN " + col + " INTEGER");
    }

    private int seedCompany() {
        try (var q = c.prepareStatement("SELECT id FROM company ORDER BY id LIMIT 1");
             var rs = q.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) { throw new Problema(clean(e.getMessage())); }
        String name = cfg("who");
        if (name.isEmpty()) name = "Empresa";
        try (var q = c.prepareStatement("INSERT INTO company(name, off, daily) VALUES(?,?,?)",
                                       java.sql.Statement.RETURN_GENERATED_KEYS)) {
            q.setString(1, name);
            q.setString(2, cfg("off"));
            q.setString(3, cfg("daily"));
            q.executeUpdate();
            try (var ks = q.getGeneratedKeys()) {
                if (ks.next()) return ks.getInt(1);
            }
        } catch (SQLException e) { throw new Problema(clean(e.getMessage())); }
        throw new Problema("company seed");
    }

    /** The id stored in cfg `company`. Always set after {@link #migrateCompanies}. */
    public int company() {
        String v = cfg("company");
        if (v.isEmpty()) throw new Problema("no company");
        try { return Integer.parseInt(v); }
        catch (NumberFormatException e) { throw new Problema("company: " + v); }
    }

    private boolean hasColumn(String table, String col) {
        try (Statement st = c.createStatement();
             var rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) if (col.equalsIgnoreCase(rs.getString("name"))) return true;
            return false;
        } catch (SQLException e) { throw new Problema(clean(e.getMessage())); }
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
