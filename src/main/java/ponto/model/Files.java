package ponto.model;

import ponto.Bad;
import ponto.NotFound;
import ponto.Problema;
import ponto.StorageFailure;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Durable object operations. Database critical sections never include network calls. */
public final class Files implements AutoCloseable {
    private static final String COLS = "id,note_id,name,key,size,ctype,crea";
    private static final String CARD_COLS = "id,card_id,name,key,size,ctype,crea";
    private final Db db;
    private final Object lock;
    private final ObjectStore s3;
    private final Object[] objects = java.util.stream.IntStream.range(0,64).mapToObj(i -> new Object()).toArray();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ponto-objects"); t.setDaemon(true); return t;
    });
    private volatile boolean started;
    private volatile boolean storageReady;
    private volatile long lastHealth;

    Files(Db db, Object lock, ObjectStore s3) { this.db = db; this.lock = lock; this.s3 = s3; }

    public static long maxUpload() {
        String raw = System.getenv().getOrDefault("PONTO_UPLOAD_MIB", "20");
        try {
            int mib = Integer.parseInt(raw);
            if (mib < 1 || mib > 100) throw new NumberFormatException();
            return mib * 1024L * 1024;
        } catch (NumberFormatException e) { throw new Problema("PONTO_UPLOAD_MIB: use 1 a 100"); }
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        if (s3 != null) worker.execute(() -> {
            try { s3.ensureBucket(); }
            catch (Problema e) { System.err.println("storage initialization unavailable; attachments remain pending"); }
        });
        worker.scheduleWithFixedDelay(this::reconcile, 0, 30, TimeUnit.SECONDS);
    }

    public boolean storageUp() {
        if (s3 == null) return false;
        long now = System.nanoTime();
        if (lastHealth == 0 || now - lastHealth > TimeUnit.SECONDS.toNanos(10)) {
            storageReady = s3.up();
            lastHealth = now;
        }
        return storageReady;
    }

    public boolean configured() { return s3 != null; }

    public List<NoteFile> list(int noteId) {
        synchronized (lock) { return rows("SELECT " + COLS + " FROM note_file WHERE note_id=? ORDER BY id", noteId); }
    }

    /** Attachment names for many notes in a single query, keyed by note id. */
    public Map<Integer, List<String>> namesByNotes(Collection<Integer> noteIds) {
        if (noteIds.isEmpty()) return Map.of();
        synchronized (lock) {
            String in = String.join(",", noteIds.stream().map(n -> "?").toList());
            try (var q = db.conn().prepareStatement("SELECT note_id, name FROM note_file WHERE note_id IN (" + in + ") ORDER BY id")) {
                int i = 1;
                for (Integer id : noteIds) q.setInt(i++, id);
                Map<Integer, List<String>> out = new LinkedHashMap<>();
                try (var rs = q.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString("name");
                        out.computeIfAbsent(rs.getInt("note_id"), k -> new ArrayList<>()).add(name == null ? "" : name);
                    }
                }
                return out;
            } catch (SQLException e) { throw problem(e); }
        }
    }

    public String state(int id) {
        synchronized (lock) {
            try (var q = db.conn().prepareStatement("SELECT state FROM note_file WHERE id=?")) {
                q.setInt(1, id);
                try (var rs = q.executeQuery()) {
                    if (!rs.next()) throw new NotFound("arquivo " + id + " não existe");
                    return rs.getString(1);
                }
            } catch (SQLException e) { throw problem(e); }
        }
    }

    public NoteFile add(int noteId, String name, String ctype, byte[] data) {
        StagedUpload up = stage(name, ctype, data, "note-" + noteId + "/");
        int id;
        try {
            id = db.transaction(() -> {
                try (var q = db.conn().prepareStatement(
                        "INSERT INTO note_file(note_id,name,key,size,ctype,state) VALUES(?,?,?,?,?,'uploading')",
                        java.sql.Statement.RETURN_GENERATED_KEYS)) {
                    q.setInt(1, noteId); q.setString(2, up.filename()); q.setString(3, up.key());
                    q.setLong(4, up.size()); q.setString(5, up.ctype());
                    q.executeUpdate();
                    queue(up.key(), "PUT", up.staged().toString(), up.ctype());
                    try (var rs = q.getGeneratedKeys()) { return rs.next() ? rs.getInt(1) : 0; }
                } catch (SQLException e) { throw problem(e); }
            });
        } catch (RuntimeException e) { deleteStaged(up.staged().toString()); throw e; }
        process(up.key());
        return must(id);
    }

    public List<KanbanCardFile> listForCard(int cardId) {
        synchronized (lock) {
            return cardRows("SELECT " + CARD_COLS + " FROM kanban_card_file WHERE card_id=? ORDER BY id", cardId);
        }
    }

    public String cardState(int id) {
        synchronized (lock) {
            try (var q = db.conn().prepareStatement("SELECT state FROM kanban_card_file WHERE id=?")) {
                q.setInt(1, id);
                try (var rs = q.executeQuery()) {
                    if (!rs.next()) throw new NotFound("arquivo " + id + " não existe");
                    return rs.getString(1);
                }
            } catch (SQLException e) { throw problem(e); }
        }
    }

    public KanbanCardFile addForCard(int cardId, String name, String ctype, byte[] data) {
        StagedUpload up = stage(name, ctype, data, "kanban-" + cardId + "/");
        int id;
        try {
            id = db.transaction(() -> {
                try (var q = db.conn().prepareStatement(
                        "INSERT INTO kanban_card_file(card_id,name,key,size,ctype,state) VALUES(?,?,?,?,?,'uploading')",
                        java.sql.Statement.RETURN_GENERATED_KEYS)) {
                    q.setInt(1, cardId); q.setString(2, up.filename()); q.setString(3, up.key());
                    q.setLong(4, up.size()); q.setString(5, up.ctype());
                    q.executeUpdate();
                    queue(up.key(), "PUT", up.staged().toString(), up.ctype());
                    try (var rs = q.getGeneratedKeys()) { return rs.next() ? rs.getInt(1) : 0; }
                } catch (SQLException e) { throw problem(e); }
            });
        } catch (RuntimeException e) { deleteStaged(up.staged().toString()); throw e; }
        process(up.key());
        return mustCardFile(id);
    }

    public NoteFile must(int id) {
        synchronized (lock) {
            var rows = rows("SELECT " + COLS + " FROM note_file WHERE id=?", id);
            if (rows.isEmpty()) throw new NotFound("arquivo " + id + " não existe");
            return rows.getFirst();
        }
    }

    public KanbanCardFile mustCardFile(int id) {
        synchronized (lock) {
            var rows = cardRows("SELECT " + CARD_COLS + " FROM kanban_card_file WHERE id=?", id);
            if (rows.isEmpty()) throw new NotFound("arquivo " + id + " não existe");
            return rows.getFirst();
        }
    }

    public byte[] download(int id) {
        NoteFile f;
        synchronized (lock) {
            f = must(id);
            if (!state(id).equals("ready")) throw new StorageFailure("Upload pendente. Tente novamente em instantes.");
        }
        return fetch(f.key(), f.size());
    }

    public byte[] downloadCardFile(int id) {
        KanbanCardFile f;
        synchronized (lock) {
            f = mustCardFile(id);
            if (!cardState(id).equals("ready")) throw new StorageFailure("Upload pendente. Tente novamente em instantes.");
        }
        return fetch(f.key(), f.size());
    }

    private byte[] fetch(String key, long size) {
        if (s3 == null) throw new StorageFailure("Armazenamento não configurado.");
        try {
            byte[] data = s3.get(key);
            if (data.length != size) throw new Problema("tamanho divergente");
            return data;
        } catch (Problema e) { throw new StorageFailure("Não foi possível baixar o arquivo. Tente novamente."); }
    }

    /** Metadata deletion and remote cleanup intent commit together. */
    public void remove(int id) {
        db.transaction(() -> {
            NoteFile f = must(id);
            queueDelete(f.key());
            write("DELETE FROM note_file WHERE id=?", id);
            return null;
        });
        kick();
    }

    public void removeCardFile(int id) {
        db.transaction(() -> {
            KanbanCardFile f = mustCardFile(id);
            queueDelete(f.key());
            write("DELETE FROM kanban_card_file WHERE id=?", id);
            return null;
        });
        kick();
    }

    /** Called inside the note-deletion transaction; the independent queue survives cascade. */
    void scheduleNoteDeletion(int noteId) {
        for (NoteFile f : list(noteId)) queueDelete(f.key());
    }

    /** Called inside the card-deletion transaction; the independent queue survives cascade. */
    void scheduleCardDeletion(int cardId) {
        for (KanbanCardFile f : listForCard(cardId)) queueDelete(f.key());
    }

    private record StagedUpload(String filename, String key, String ctype, long size, Path staged) { }

    private StagedUpload stage(String name, String ctype, byte[] data, String keyPrefix) {
        if (s3 == null) throw new StorageFailure("Armazenamento não configurado.");
        if (data.length == 0 || data.length > maxUpload()) throw new Bad("Arquivo vazio ou maior que o limite de upload.");
        String safe = name == null ? "file" : name.replace('\\', '/');
        safe = safe.substring(safe.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        if (safe.isBlank()) safe = "file";
        String key = keyPrefix + UUID.randomUUID() + "-" + safe;
        try {
            Path dir = db.dataDir.resolve("uploads");
            java.nio.file.Files.createDirectories(dir);
            Path staged = java.nio.file.Files.createTempFile(dir, "upload-", ".bin");
            java.nio.file.Files.write(staged, data);
            return new StagedUpload(safe, key, ctype == null ? "" : ctype, data.length, staged);
        } catch (java.io.IOException e) { throw new Problema("Não foi possível preparar o upload."); }
    }

    private void queueDelete(String key) {
        String path = "";
        Job current = job(key);
        if (current != null) path = current.path();
        queue(key, "DELETE", path, "");
    }

    private void queue(String key, String operation, String path, String ctype) {
        try (var q = db.conn().prepareStatement("INSERT INTO object_job(key,operation,path,ctype) VALUES(?,?,?,?) "
                + "ON CONFLICT(key) DO UPDATE SET operation=excluded.operation,path=excluded.path,ctype=excluded.ctype,attempts=0,last_error=''")) {
            q.setString(1, key); q.setString(2, operation); q.setString(3, path); q.setString(4, ctype == null ? "" : ctype);
            q.executeUpdate();
        } catch (SQLException e) { throw problem(e); }
    }

    private record Job(String key, String operation, String path, String ctype, int attempts) { }

    private Job job(String key) {
        synchronized (lock) {
            try (var q = db.conn().prepareStatement("SELECT key,operation,path,ctype,attempts FROM object_job WHERE key=?")) {
                q.setString(1, key);
                try (var rs = q.executeQuery()) {
                    return rs.next() ? new Job(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5)) : null;
                }
            } catch (SQLException e) { throw problem(e); }
        }
    }

    private void process(String key) {
        synchronized (objects[(key.hashCode() & Integer.MAX_VALUE) % objects.length]) {
            Job job = job(key);
            if (job == null || job.attempts() >= 3 || s3 == null) return;
            try {
                if (job.operation().equals("PUT")) {
                    byte[] data = java.nio.file.Files.readAllBytes(Path.of(job.path()));
                    s3.ensureBucket(); s3.put(key, data, job.ctype());
                } else s3.delete(key);
                db.transaction(() -> {
                    Job latest = job(key);
                    // A note/card may be deleted while its upload is in flight. Keep that DELETE queued.
                    if (latest != null && latest.operation().equals(job.operation())) {
                        write("UPDATE note_file SET state='ready' WHERE key=?", key);
                        write("UPDATE kanban_card_file SET state='ready' WHERE key=?", key);
                        write("DELETE FROM object_job WHERE key=?", key);
                    }
                    return null;
                });
                deleteStaged(job.path());
            } catch (Exception e) {
                db.transaction(() -> {
                    Job latest = job(key);
                    if (latest != null && latest.operation().equals(job.operation())) {
                        write("UPDATE object_job SET attempts=attempts+1,last_error='armazenamento indisponível' WHERE key=?", key);
                        write("UPDATE note_file SET state='failed' WHERE key=?", key);
                        write("UPDATE kanban_card_file SET state='failed' WHERE key=?", key);
                    }
                    return null;
                });
                System.err.println("object operation pending: " + job.operation() + " " + key);
                throw new StorageFailure("Arquivo pendente no armazenamento. A aplicação tentará novamente; não é necessário reenviar.");
            }
        }
    }

    /** Bounded retries; exhausted jobs stay visible and may be explicitly retried. */
    public void reconcile() {
        if (s3 == null) return;
        List<String> keys = new ArrayList<>();
        synchronized (lock) {
            try (var q = db.conn().createStatement(); var rs = q.executeQuery("SELECT key FROM object_job WHERE attempts<3 ORDER BY key")) {
                while (rs.next()) keys.add(rs.getString(1));
            } catch (SQLException e) { System.err.println("object queue: " + e.getMessage()); return; }
        }
        for (String key : keys) {
            if (Thread.currentThread().isInterrupted()) break;
            try { process(key); } catch (StorageFailure ignored) { /* durable queue retains failures */ }
        }
    }

    public int pending() {
        synchronized (lock) {
            try (var q = db.conn().createStatement(); var rs = q.executeQuery("SELECT count(*) FROM object_job")) { return rs.next() ? rs.getInt(1) : 0; }
            catch (SQLException e) { throw problem(e); }
        }
    }

    public void retry() {
        synchronized (lock) { write("UPDATE object_job SET attempts=0,last_error=''"); }
        kick();
    }

    public void kick() { if (!worker.isShutdown()) worker.execute(this::reconcile); }

    private List<NoteFile> rows(String sql, int id) {
        try (var q = db.conn().prepareStatement(sql)) {
            q.setInt(1, id);
            try (var rs = q.executeQuery()) {
                List<NoteFile> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        } catch (SQLException e) { throw problem(e); }
    }

    private List<KanbanCardFile> cardRows(String sql, int id) {
        try (var q = db.conn().prepareStatement(sql)) {
            q.setInt(1, id);
            try (var rs = q.executeQuery()) {
                List<KanbanCardFile> out = new ArrayList<>();
                while (rs.next()) out.add(mapCard(rs));
                return out;
            }
        } catch (SQLException e) { throw problem(e); }
    }

    private void write(String sql, Object... values) {
        try (var q = db.conn().prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) q.setObject(i+1, values[i]);
            q.executeUpdate();
        } catch (SQLException e) { throw problem(e); }
    }

    private static NoteFile map(ResultSet rs) throws SQLException {
        return new NoteFile(rs.getInt("id"), rs.getInt("note_id"), rs.getString("name"), rs.getString("key"),
                rs.getLong("size"), rs.getString("ctype"), rs.getString("crea"));
    }

    private static KanbanCardFile mapCard(ResultSet rs) throws SQLException {
        return new KanbanCardFile(rs.getInt("id"), rs.getInt("card_id"), rs.getString("name"), rs.getString("key"),
                rs.getLong("size"), rs.getString("ctype"), rs.getString("crea"));
    }

    private static void deleteStaged(String path) {
        if (path == null || path.isBlank()) return;
        try { java.nio.file.Files.deleteIfExists(Path.of(path)); }
        catch (java.io.IOException e) { System.err.println("pending upload cleanup: " + path); }
    }

    private static Problema problem(SQLException e) { return new Problema(Db.clean(e.getMessage())); }
    @Override public void close() {
        worker.shutdownNow();
        try { worker.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
