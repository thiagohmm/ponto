package ponto.model;

import ponto.Bad;
import ponto.Problema;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Workplaces in the same SQLite file. Shares Service's lock: the JDBC
 * connection is not thread-safe.
 */
public final class Companies {

    private static final String COLS = "id, name, off, daily, crea";

    private final Db db;
    private final Object lock;

    Companies(Db db, Object lock) { this.db = db; this.lock = lock; }

    public List<Company> list() {
        synchronized (lock) { return listLocked(); }
    }

    public Company byId(int id) {
        synchronized (lock) { return must(id); }
    }

    public Company active() {
        synchronized (lock) { return must(db.company()); }
    }

    public Company create(String name, String off, String daily) {
        synchronized (lock) {
            String n = trimName(name);
            String o = nzOff(off);
            String d = nzDaily(daily);
            int id = insert("INSERT INTO company(name, off, daily) VALUES(?,?,?)", n, o, d);
            return must(id);
        }
    }

    public Company update(int id, String name, String off, String daily) {
        synchronized (lock) {
            Company cur = must(id);
            String n = name == null ? cur.name() : trimName(name);
            String o = off == null ? cur.off() : nzOff(off);
            String d = daily == null ? cur.daily() : nzDaily(daily);
            write("UPDATE company SET name=?, off=?, daily=? WHERE id=?", n, o, d, id);
            return must(id);
        }
    }

    public void activate(int id) {
        if (db.scoped()) throw new Bad("a seleção web pertence à aba");
        synchronized (lock) {
            must(id);
            db.setCfg("company", String.valueOf(id));
        }
    }

    public void delete(int id) {
        synchronized (lock) {
            db.transaction(() -> {
            List<Company> all = listLocked();
            if (all.size() <= 1) throw new Bad("última empresa");
            must(id);
            if (countTable("ponto", id) > 0 || countTable("note", id) > 0)
                throw new Bad("empresa com dados");
            write("DELETE FROM folder WHERE company_id=?", id);
            write("DELETE FROM tag WHERE company_id=?", id);
            write("DELETE FROM dia_flag WHERE company_id=?", id);
            write("DELETE FROM company WHERE id=?", id);
            if (db.company() == id) {
                int next = all.stream().filter(c -> c.id() != id).findFirst().orElseThrow().id();
                db.setCfg("company", String.valueOf(next));
            }

            return null;
            });
        }
    }

    private List<Company> listLocked() {
        return rows("SELECT " + COLS + " FROM company ORDER BY name COLLATE NOCASE, id");
    }

    private Company must(int id) {
        List<Company> r = rows("SELECT " + COLS + " FROM company WHERE id = ?", id);
        if (r.isEmpty()) throw new ponto.NotFound("empresa " + id + " não existe");
        return r.getFirst();
    }

    private int countTable(String table, int id) {
        try (var q = prep("SELECT count(*) FROM " + table + " WHERE company_id = ?")) {
            q.setInt(1, id);
            try (var rs = q.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException e) { throw new Problema(clean(e)); }
    }

    private static String trimName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.equals("-")) throw new Bad("nome da empresa");
        return n;
    }

    private static String nzOff(String off) {
        if (off == null || off.isBlank() || off.equals("-")) return "";
        return off.trim();
    }

    private static String nzDaily(String daily) {
        if (daily == null || daily.isBlank() || daily.equals("-")) return "";
        return Service.dailyLabel(Service.parseDailyMins(daily));
    }

    private List<Company> rows(String sql, Object... args) {
        try (var q = prep(sql)) {
            bind(q, args);
            try (var rs = q.executeQuery()) {
                List<Company> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        } catch (SQLException e) { throw new Problema(clean(e)); }
    }

    private int write(String sql, Object... args) {
        try (var q = prep(sql)) {
            bind(q, args);
            return q.executeUpdate();
        } catch (SQLException e) { throw new Problema(clean(e)); }
    }

    private int insert(String sql, Object... args) {
        try (var q = prep(sql)) {
            bind(q, args);
            q.executeUpdate();
            try (var ks = q.getGeneratedKeys()) { return ks.next() ? ks.getInt(1) : 0; }
        } catch (SQLException e) { throw new Problema(clean(e)); }
    }

    private PreparedStatement prep(String sql) throws SQLException {
        return db.conn().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
    }

    private static void bind(PreparedStatement q, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            int n = i + 1;
            if (a == null) q.setNull(n, Types.INTEGER);
            else if (a instanceof Integer v) q.setInt(n, v);
            else q.setString(n, a.toString());
        }
    }

    private static Company map(ResultSet rs) throws SQLException {
        return new Company(rs.getInt("id"), nz(rs.getString("name")), nz(rs.getString("off")),
                           nz(rs.getString("daily")), nz(rs.getString("crea")));
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String clean(SQLException e) { return Db.clean(e.getMessage()); }
}
