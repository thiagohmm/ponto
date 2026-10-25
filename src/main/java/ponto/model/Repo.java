package ponto.model;

import ponto.Problema;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * CRUD over the `ponto` table. Nothing is hidden behind "today": any row can be
 * read, changed (day / in / out / justification) or deleted by its id.
 */
public final class Repo {

    private static final String COLS = "id, dia, tin, tout, just, crea";
    private static final String ORDER = " ORDER BY dia DESC, ifnull(tin,'99:99') DESC, id DESC";

    private final Db db;

    public Repo(Db db) { this.db = db; }

    private List<Entry> rows(String sql, Object... args) {
        try (var q = prep(sql)) {
            bind(q, args);
            try (var rs = q.executeQuery()) {
                List<Entry> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        } catch (SQLException e) { throw new Problema(clean(e)); }
    }

    /** UPDATE/DELETE: the number of rows actually touched. */
    private int write(String sql, Object... args) {
        try (var q = prep(sql)) {
            bind(q, args);
            return q.executeUpdate();
        } catch (SQLException e) { throw new Problema(clean(e)); }
    }

    /** INSERT: the new id. */
    private int insertId(String sql, Object... args) {
        try (var q = prep(sql)) {
            bind(q, args);
            q.executeUpdate();
            try (var ks = q.getGeneratedKeys()) { return ks.next() ? ks.getInt(1) : 0; }
        } catch (SQLException e) { throw new Problema(clean(e)); }
    }

    private PreparedStatement prep(String sql) {
        try { return db.conn().prepareStatement(sql); }
        catch (SQLException e) { throw new Problema(clean(e)); }
    }

    private static void bind(PreparedStatement q, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            int n = i + 1;
            if (a == null) q.setNull(n, java.sql.Types.NULL);
            else if (a instanceof LocalDate d) q.setString(n, Str.iso(d));
            else if (a instanceof LocalTime t) q.setString(n, Str.t(t));
            else if (a instanceof Integer v) q.setInt(n, v);
            else q.setString(n, a.toString());
        }
    }

    private static Entry map(ResultSet rs) throws SQLException {
        String tin = rs.getString("tin"), tout = rs.getString("tout");
        return new Entry(rs.getInt("id"), LocalDate.parse(rs.getString("dia")),
                         at(tin), at(tout), nz(rs.getString("just")), nz(rs.getString("crea")));
    }

    private static LocalTime at(String s) {
        if (s == null || s.isBlank()) return null;
        int i = s.indexOf(':');
        return LocalTime.of(Integer.parseInt(s.substring(0, i)), Integer.parseInt(s.substring(i + 1)));
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String clean(SQLException e) { return Db.clean(e.getMessage()); }

    // ---- reads -------------------------------------------------------------

    List<Entry> recent(int n) { return recent(n, 0); }

    /** The newest rows, skipping the first `skip` (offset paging; see App.list). */
    public List<Entry> recent(int n, int skip) {
        return rows("SELECT " + COLS + " FROM ponto" + ORDER + " LIMIT ? OFFSET ?", n, Math.max(0, skip));
    }

    public List<Entry> between(LocalDate a, LocalDate b) {
        return rows("SELECT " + COLS + " FROM ponto WHERE dia BETWEEN ? AND ?" + ORDER, a, b);
    }

    public List<Entry> on(LocalDate d) { return between(d, d); }

    /** Ids in range, most recent first (used by the list/summary tables). */
    List<Entry> all() { return rows("SELECT " + COLS + " FROM ponto" + ORDER); }

    /**
     * Rows whose ISO day matches `like` (`2026-10-08` · `%2026%` · `2026-10%`),
     * newest first, offset paging so `older` walks to the last one.
     */
    public List<Entry> byDay(String like, int n, int skip) {
        return rows("SELECT " + COLS + " FROM ponto WHERE dia LIKE ?" + ORDER + " LIMIT ? OFFSET ?",
                    like, n, Math.max(0, skip));
    }

    /** How many rows match that day pattern (the `of M` in the footer). */
    public int countDay(String like) {
        try (var q = prep("SELECT count(*) FROM ponto WHERE dia LIKE ?")) {
            q.setString(1, like);
            try (var rs = q.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException e) { throw new Problema(clean(e)); }
    }

    public List<Entry> search(String q) {
        String like = "%" + q.trim().replace('%', ' ').replace('_', ' ') + "%";
        return rows("SELECT " + COLS + " FROM ponto WHERE just LIKE ? COLLATE NOCASE" + ORDER, like);
    }

    public Entry byId(int id) {
        List<Entry> r = rows("SELECT " + COLS + " FROM ponto WHERE id = ?", id);
        if (r.isEmpty()) throw new Problema("no record with id " + id);
        return r.getFirst();
    }

    /** Lines whose day has an in without an out (or vice versa). */
    /** Unclosed lines of any day, oldest first (the banner counts these). */
    public List<Entry> openAll() {
        return rows("SELECT " + COLS + " FROM ponto"
                  + " WHERE tin IS NULL OR tout IS NULL"
                  + " ORDER BY dia ASC, ifnull(tin,'99:99') ASC, id ASC");
    }

    List<Entry> open(LocalDate d) {
        return rows("SELECT " + COLS + " FROM ponto WHERE dia = ? AND ((tin IS NOT NULL AND tout IS NULL)"
                  + " OR (tin IS NULL AND tout IS NOT NULL))" + ORDER, d);
    }

    public int count() {
        try (var q = prep("SELECT count(*) FROM ponto")) {
            try (var rs = q.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException e) { throw new Problema(clean(e)); }
    }

    /** First/last recorded day (defaults for the export period). */
    public LocalDate min() {
        try (var q = prep("SELECT min(dia) FROM ponto")) {
            try (var rs = q.executeQuery()) {
                if (!rs.next()) return null;
                String v = rs.getString(1);
                return v == null ? null : LocalDate.parse(v);
            }
        } catch (SQLException e) { throw new Problema(clean(e)); }
    }

    public LocalDate max() {
        try (var q = prep("SELECT max(dia) FROM ponto")) {
            try (var rs = q.executeQuery()) {
                if (!rs.next()) return null;
                String v = rs.getString(1);
                return v == null ? null : LocalDate.parse(v);
            }
        } catch (SQLException e) { throw new Problema(clean(e)); }
    }

    // ---- writes ----------------------------------------------------------

    public int insert(LocalDate dia, LocalTime tin, LocalTime tout, String just) {
        return insertId("INSERT INTO ponto(dia,tin,tout,just) VALUES(?,?,?,?)", dia, tin, tout, just);
    }

    /** Patch one field of one record: the UI always sends the whole row. */
    public void update(Entry e) {
        write("UPDATE ponto SET dia=?, tin=?, tout=?, just=? WHERE id=?", e.dia(), e.tin(), e.tout(), e.just(), e.id());
    }

    /** Clear just the in/out pair (keeps the day + justification). */
    public void clearTimes(Entry e) {
        write("UPDATE ponto SET tin=NULL, tout=NULL WHERE id=?", e.id());
    }

    public void delete(int id) {
        int n = write("DELETE FROM ponto WHERE id=?", id);
        if (n == 0) throw new Problema("no record with id " + id);
    }

    /** Delete every record of a day (used by the day editor). */
    public int deleteDay(LocalDate d) { return write("DELETE FROM ponto WHERE dia = ?", d); }

    /** Snap a new timesheet for a brand new day: copies nothing, just inserts. */
    public int countOn(LocalDate d) { return on(d).size(); }
}
