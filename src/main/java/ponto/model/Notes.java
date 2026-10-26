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
 * Markdown notes in the same SQLite file, different table.
 * Shares Service's lock: the JDBC connection is not thread-safe.
 */
public final class Notes {

    private static final String COLS = "id, title, body, crea, upd, folder_id";
    private static final String ORDER = " ORDER BY upd DESC, id DESC";
    private static final String FCOLS = "id, parent, name, crea";

    private final Db db;
    private final Object lock;

    Notes(Db db, Object lock) { this.db = db; this.lock = lock; }

    public List<Note> list(String q) {
        synchronized (lock) {
            if (q == null || q.isBlank())
                return rows("SELECT " + COLS + " FROM note" + ORDER);
            String like = "%" + q.trim().replace('%', ' ').replace('_', ' ') + "%";
            return rows("SELECT " + COLS + " FROM note WHERE title LIKE ? COLLATE NOCASE"
                        + " OR body LIKE ? COLLATE NOCASE" + ORDER, like, like);
        }
    }

    public Note byId(int id) {
        synchronized (lock) {
            List<Note> r = rows("SELECT " + COLS + " FROM note WHERE id = ?", id);
            if (r.isEmpty()) throw new Problema("no note with id " + id);
            return r.getFirst();
        }
    }

    /** First note whose title matches, case-insensitive; null if none. */
    public Note byTitle(String title) {
        synchronized (lock) {
            if (title == null || title.isBlank()) return null;
            List<Note> r = rows("SELECT " + COLS + " FROM note WHERE title = ? COLLATE NOCASE"
                                + ORDER + " LIMIT 1", title.trim());
            return r.isEmpty() ? null : r.getFirst();
        }
    }

    public Note create(String title, String body) { return create(title, body, null); }

    public Note create(String title, String body, Integer folderId) {
        synchronized (lock) {
            Integer fid = folder(folderId);
            if (fid != null) mustFolder(fid);
            int id = insert("INSERT INTO note(title, body, folder_id) VALUES(?,?,?)",
                            nz(title), nz(body), fid);
            return must(id);
        }
    }

    public Note update(int id, String title, String body) {
        synchronized (lock) {
            must(id);
            write("UPDATE note SET title=?, body=?,"
                  + " upd=strftime('%Y-%m-%d %H:%M','now','localtime') WHERE id=?",
                  nz(title), nz(body), id);
            return must(id);
        }
    }

    public Note move(int id, Integer folderId) {
        synchronized (lock) {
            must(id);
            Integer fid = folder(folderId);
            if (fid != null) mustFolder(fid);
            write("UPDATE note SET folder_id=? WHERE id=?", fid, id);
            return must(id);
        }
    }

    public void delete(int id) {
        synchronized (lock) {
            int n = write("DELETE FROM note WHERE id=?", id);
            if (n == 0) throw new Problema("no note with id " + id);
        }
    }

    public List<Folder> folders() {
        synchronized (lock) {
            return folders("SELECT " + FCOLS + " FROM folder ORDER BY name COLLATE NOCASE, id");
        }
    }

    public Folder createFolder(String name, Integer parent) {
        synchronized (lock) {
            Integer p = folder(parent);
            if (p != null) mustFolder(p);
            String n = name == null ? "" : name.trim();
            if (n.isEmpty()) n = "Pasta";
            int id = insert("INSERT INTO folder(parent, name) VALUES(?,?)", p, n);
            return mustFolder(id);
        }
    }

    public Folder renameFolder(int id, String name) {
        synchronized (lock) {
            mustFolder(id);
            String n = name == null ? "" : name.trim();
            if (n.isEmpty()) throw new Bad("folder name required");
            write("UPDATE folder SET name=? WHERE id=?", n, id);
            return mustFolder(id);
        }
    }

    public Folder moveFolder(int id, Integer parent) {
        synchronized (lock) {
            mustFolder(id);
            Integer p = folder(parent);
            if (p != null) {
                if (p == id) throw new Bad("folder inside itself");
                mustFolder(p);
                Integer walk = p;
                while (walk != null) {
                    if (walk == id) throw new Bad("folder inside itself");
                    walk = mustFolder(walk).parent();
                }
            }
            write("UPDATE folder SET parent=? WHERE id=?", p, id);
            return mustFolder(id);
        }
    }

    /** Notes and child folders go to the parent (or the root). */
    public void deleteFolder(int id) {
        synchronized (lock) {
            Folder f = mustFolder(id);
            write("UPDATE note SET folder_id=? WHERE folder_id=?", f.parent(), id);
            write("UPDATE folder SET parent=? WHERE parent=?", f.parent(), id);
            write("DELETE FROM folder WHERE id=?", id);
        }
    }

    private Note must(int id) {
        List<Note> r = rows("SELECT " + COLS + " FROM note WHERE id = ?", id);
        if (r.isEmpty()) throw new Problema("no note with id " + id);
        return r.getFirst();
    }

    private Folder mustFolder(int id) {
        List<Folder> r = folders("SELECT " + FCOLS + " FROM folder WHERE id = ?", id);
        if (r.isEmpty()) throw new Problema("no folder with id " + id);
        return r.getFirst();
    }

    private static Integer folder(Integer id) {
        if (id == null || id <= 0) return null;
        return id;
    }

    private List<Note> rows(String sql, Object... args) {
        try (var q = prep(sql)) {
            bind(q, args);
            try (var rs = q.executeQuery()) {
                List<Note> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        } catch (SQLException e) { throw new Problema(clean(e)); }
    }

    private List<Folder> folders(String sql, Object... args) {
        try (var q = prep(sql)) {
            bind(q, args);
            try (var rs = q.executeQuery()) {
                List<Folder> out = new ArrayList<>();
                while (rs.next()) {
                    int p = rs.getInt("parent");
                    out.add(new Folder(rs.getInt("id"), rs.wasNull() ? null : p,
                                       nz(rs.getString("name")), nz(rs.getString("crea"))));
                }
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

    private static Note map(ResultSet rs) throws SQLException {
        int f = rs.getInt("folder_id");
        Integer folderId = rs.wasNull() ? null : f;
        return new Note(rs.getInt("id"), nz(rs.getString("title")), nz(rs.getString("body")),
                        nz(rs.getString("crea")), nz(rs.getString("upd")), folderId);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String clean(SQLException e) { return Db.clean(e.getMessage()); }
}
