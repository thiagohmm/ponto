package ponto.model;

import ponto.Bad;
import ponto.Problema;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Markdown notes in the same SQLite file, different table.
 * Shares Service's lock: the JDBC connection is not thread-safe.
 */
public final class Notes {

    private static final String COLS = "id, title, body, crea, upd, folder_id";
    private static final String ORDER = " ORDER BY upd DESC, id DESC";
    private static final String FCOLS = "id, parent, name, crea";
    private static final String TCOLS = "id, name, color, crea";
    private static final String[] TAG_COLORS = {
        "#007aff", "#34c759", "#ff9f0a", "#ff3b30",
        "#af52de", "#5856d6", "#ff2d55", "#64d2ff"
    };

    private final Db db;
    private final Object lock;

    Notes(Db db, Object lock) { this.db = db; this.lock = lock; }

    public List<Note> list(String q) {
        synchronized (lock) {
            if (q == null || q.isBlank())
                return attachTags(rows("SELECT " + COLS + " FROM note" + ORDER));
            String like = "%" + q.trim().replace('%', ' ').replace('_', ' ') + "%";
            return attachTags(rows(
                "SELECT DISTINCT note.id, note.title, note.body, note.crea, note.upd, note.folder_id"
                + " FROM note LEFT JOIN note_tag ON note_tag.note_id = note.id"
                + " LEFT JOIN tag ON tag.id = note_tag.tag_id"
                + " WHERE note.title LIKE ? COLLATE NOCASE OR note.body LIKE ? COLLATE NOCASE"
                + " OR tag.name LIKE ? COLLATE NOCASE"
                + " ORDER BY note.upd DESC, note.id DESC", like, like, like));
        }
    }

    public Note byId(int id) {
        synchronized (lock) {
            List<Note> r = attachTags(rows("SELECT " + COLS + " FROM note WHERE id = ?", id));
            if (r.isEmpty()) throw new Problema("no note with id " + id);
            return r.getFirst();
        }
    }

    /** First note whose title matches, case-insensitive; null if none. */
    public Note byTitle(String title) {
        synchronized (lock) {
            if (title == null || title.isBlank()) return null;
            List<Note> r = attachTags(rows("SELECT " + COLS + " FROM note WHERE title = ? COLLATE NOCASE"
                                + ORDER + " LIMIT 1", title.trim()));
            return r.isEmpty() ? null : r.getFirst();
        }
    }

    public Note create(String title, String body) { return create(title, body, null, null); }

    public Note create(String title, String body, Integer folderId) {
        return create(title, body, folderId, null);
    }

    public Note create(String title, String body, Integer folderId, List<Integer> tagIds) {
        synchronized (lock) {
            Integer fid = folder(folderId);
            if (fid != null) mustFolder(fid);
            int id = insert("INSERT INTO note(title, body, folder_id) VALUES(?,?,?)",
                            nz(title), nz(body), fid);
            putTags(id, tagIds);
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
            write("DELETE FROM note_tag WHERE note_id=?", id);
            int n = write("DELETE FROM note WHERE id=?", id);
            if (n == 0) throw new Problema("no note with id " + id);
        }
    }

    public Note setTags(int id, List<Integer> tagIds) {
        synchronized (lock) {
            must(id);
            write("DELETE FROM note_tag WHERE note_id=?", id);
            putTags(id, tagIds);
            return must(id);
        }
    }

    public Note addTag(int id, int tagId) {
        synchronized (lock) {
            must(id);
            mustTag(tagId);
            write("INSERT OR IGNORE INTO note_tag(note_id, tag_id) VALUES(?,?)", id, tagId);
            return must(id);
        }
    }

    public List<Tag> tags() {
        synchronized (lock) {
            return tags("SELECT " + TCOLS + " FROM tag ORDER BY name COLLATE NOCASE, id");
        }
    }

    public Tag createTag(String name, String color) {
        synchronized (lock) {
            String n = name == null ? "" : name.trim();
            if (n.isEmpty()) n = "Tag";
            List<Tag> same = tags("SELECT " + TCOLS + " FROM tag WHERE name = ? COLLATE NOCASE", n);
            if (!same.isEmpty()) return same.getFirst();
            String c = colorOf(color);
            int id = insert("INSERT INTO tag(name, color) VALUES(?,?)", n, c);
            return mustTag(id);
        }
    }

    public Tag renameTag(int id, String name, String color) {
        synchronized (lock) {
            Tag cur = mustTag(id);
            String n = name == null ? cur.name() : name.trim();
            if (n.isEmpty()) throw new Bad("tag name required");
            String c = color == null || color.isBlank() ? cur.color() : colorOf(color);
            write("UPDATE tag SET name=?, color=? WHERE id=?", n, c, id);
            return mustTag(id);
        }
    }

    public void deleteTag(int id) {
        synchronized (lock) {
            mustTag(id);
            write("DELETE FROM note_tag WHERE tag_id=?", id);
            write("DELETE FROM tag WHERE id=?", id);
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
        List<Note> r = attachTags(rows("SELECT " + COLS + " FROM note WHERE id = ?", id));
        if (r.isEmpty()) throw new Problema("no note with id " + id);
        return r.getFirst();
    }

    private Tag mustTag(int id) {
        List<Tag> r = tags("SELECT " + TCOLS + " FROM tag WHERE id = ?", id);
        if (r.isEmpty()) throw new Problema("no tag with id " + id);
        return r.getFirst();
    }

    private void putTags(int noteId, List<Integer> tagIds) {
        if (tagIds == null) return;
        for (Integer t : tagIds) {
            if (t == null || t <= 0) continue;
            mustTag(t);
            write("INSERT OR IGNORE INTO note_tag(note_id, tag_id) VALUES(?,?)", noteId, t);
        }
    }

    private String colorOf(String color) {
        if (color != null && color.matches("#[0-9A-Fa-f]{6}")) return color.toLowerCase();
        int n = 0;
        try (var q = prep("SELECT COUNT(*) FROM tag")) {
            try (var rs = q.executeQuery()) { if (rs.next()) n = rs.getInt(1); }
        } catch (SQLException e) { throw new Problema(clean(e)); }
        return TAG_COLORS[n % TAG_COLORS.length];
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

    private List<Note> attachTags(List<Note> notes) {
        if (notes.isEmpty()) return notes;
        Map<Integer, List<Integer>> by = new LinkedHashMap<>();
        for (Note n : notes) by.put(n.id(), new ArrayList<>());
        String in = String.join(",", notes.stream().map(n -> "?").toList());
        try (var q = prep("SELECT note_id, tag_id FROM note_tag WHERE note_id IN (" + in + ") ORDER BY tag_id")) {
            int i = 1;
            for (Note n : notes) q.setInt(i++, n.id());
            try (var rs = q.executeQuery()) {
                while (rs.next()) {
                    List<Integer> ids = by.get(rs.getInt("note_id"));
                    if (ids != null) ids.add(rs.getInt("tag_id"));
                }
            }
        } catch (SQLException e) { throw new Problema(clean(e)); }
        List<Note> out = new ArrayList<>();
        for (Note n : notes)
            out.add(new Note(n.id(), n.title(), n.body(), n.crea(), n.upd(), n.folderId(),
                             List.copyOf(by.get(n.id()))));
        return out;
    }

    private List<Tag> tags(String sql, Object... args) {
        try (var q = prep(sql)) {
            bind(q, args);
            try (var rs = q.executeQuery()) {
                List<Tag> out = new ArrayList<>();
                while (rs.next())
                    out.add(new Tag(rs.getInt("id"), nz(rs.getString("name")),
                                    nz(rs.getString("color")), nz(rs.getString("crea"))));
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
                        nz(rs.getString("crea")), nz(rs.getString("upd")), folderId, List.of());
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String clean(SQLException e) { return Db.clean(e.getMessage()); }
}
