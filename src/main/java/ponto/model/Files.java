package ponto.model;

import ponto.Problema;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Files attached to notes. The bytes go to S3 (Minio); this table is the
 * index. A note without storage configured can still list/remove rows.
 */
public final class Files {

    private static final String COLS = "id, note_id, name, key, size, ctype, crea";

    private final Db db;
    private final Object lock;
    private final Minio s3;

    Files(Db db, Object lock, Minio s3) { this.db = db; this.lock = lock; this.s3 = s3; }

    public List<NoteFile> list(int noteId) {
        synchronized (lock) {
            try (var q = prep("SELECT " + COLS + " FROM note_file WHERE note_id = ? ORDER BY id")) {
                q.setInt(1, noteId);
                try (var rs = q.executeQuery()) {
                    List<NoteFile> out = new ArrayList<>();
                    while (rs.next()) out.add(map(rs));
                    return out;
                }
            } catch (SQLException e) { throw new Problema(clean(e)); }
        }
    }

    /** Store the bytes, then keep the row; an upload failure leaves no row. */
    public NoteFile add(int noteId, String name, String ctype, byte[] data) {
        synchronized (lock) {
            requireS3();
            String safe = name == null ? "file" : name;
            int i = Math.max(safe.lastIndexOf('/'), safe.lastIndexOf('\\'));
            if (i >= 0) safe = safe.substring(i + 1);
            if (safe.isBlank()) safe = "file";
            int id = insert("INSERT INTO note_file(note_id, name, key, size, ctype) VALUES(?,?,?,?,?)",
                    noteId, safe, "", data.length, nz(ctype));
            String key = "note-" + noteId + "/" + id + "-" + UUID.randomUUID().toString().substring(0, 8) + "-" + safe;
            write("UPDATE note_file SET key=? WHERE id=?", key, id);
            try {
                s3.ensureBucket();
                s3.put(key, data, ctype);
            } catch (Problema e) {
                write("DELETE FROM note_file WHERE id=?", id);
                throw e;
            }
            return must(id);
        }
    }

    public NoteFile must(int id) {
        synchronized (lock) {
            List<NoteFile> r = rows("SELECT " + COLS + " FROM note_file WHERE id = ?", id);
            if (r.isEmpty()) throw new Problema("no file with id " + id);
            return r.getFirst();
        }
    }

    /** The stored bytes, straight from S3. */
    public byte[] download(int id) {
        synchronized (lock) {
            requireS3();
            NoteFile f = must(id);
            byte[] data = s3.get(f.key());
            if (data.length != f.size())
                throw new Problema("file " + id + " is not the size it was stored with");
            return data;
        }
    }

    public void remove(int id) {
        synchronized (lock) {
            NoteFile f = must(id);
            if (s3 != null) s3.delete(f.key());
            int n = write("DELETE FROM note_file WHERE id=?", id);
            if (n == 0) throw new Problema("no file with id " + id);
        }
    }

    /** A note is gone: its files go with it (S3 objects first, then rows). */
    public void dropNote(int noteId) {
        synchronized (lock) {
            for (NoteFile f : list(noteId))
                if (s3 != null) s3.delete(f.key());
            write("DELETE FROM note_file WHERE note_id=?", noteId);
        }
    }

    private void requireS3() {
        if (s3 == null)
            throw new Problema("storage: " + Minio.ENDPOINT + " is not set");
    }

    private List<NoteFile> rows(String sql, Object... args) {
        try (var q = prep(sql)) {
            bind(q, args);
            try (var rs = q.executeQuery()) {
                List<NoteFile> out = new ArrayList<>();
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
            if (a == null) q.setNull(n, java.sql.Types.INTEGER);
            else if (a instanceof Integer v) q.setInt(n, v);
            else if (a instanceof Long v) q.setLong(n, v);
            else q.setString(n, a.toString());
        }
    }

    private static NoteFile map(ResultSet rs) throws SQLException {
        int note = rs.getInt("note_id");
        long size = rs.getLong("size");
        return new NoteFile(rs.getInt("id"), note, nz(rs.getString("name")), nz(rs.getString("key")),
                size, nz(rs.getString("ctype")), nz(rs.getString("crea")));
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String clean(SQLException e) { return Db.clean(e.getMessage()); }
}
