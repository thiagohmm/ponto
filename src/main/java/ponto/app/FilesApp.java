package ponto.app;

import ponto.infra.Files;
import ponto.infra.Minio;
import ponto.infra.Db;
import ponto.model.KanbanCardFile;
import ponto.model.NoteFile;
import ponto.model.ObjectStore;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Application API for note/kanban attachments (PG index + object store). */
public final class FilesApp implements AutoCloseable {
    private final Db db;
    private final Object lock;
    private Files files;
    private ObjectStore storage;

    FilesApp(Db db, Object lock) {
        this.db = db;
        this.lock = lock;
    }

    /** Share storage/worker across company scopes. */
    void adopt(FilesApp other) {
        synchronized (lock) {
            this.storage = other.storage;
            this.files = other.files;
        }
    }

    Files raw() {
        synchronized (lock) {
            if (storage == null) storage = Minio.env();
            if (files == null) files = new Files(db, lock, storage);
            return files;
        }
    }

    /** Test hook with an injected object store. */
    public Files raw(ObjectStore store) {
        synchronized (lock) {
            this.storage = store;
            this.files = new Files(db, lock, store);
            return files;
        }
    }

    public void start() { raw().start(); }
    public void close() { if (files != null) files.close(); }
    public void retry() { raw().retry(); }
    public boolean configured() { return raw().configured(); }
    public boolean storageUp() { return raw().storageUp(); }
    public int pending() { return raw().pending(); }
    public static long maxUpload() { return Files.maxUpload(); }

    public Map<Integer, List<String>> namesByNotes(Collection<Integer> noteIds) {
        return raw().namesByNotes(noteIds);
    }
    public List<NoteFile> list(int noteId) { return raw().list(noteId); }
    public NoteFile add(int noteId, String name, String ctype, byte[] data) {
        return raw().add(noteId, name, ctype, data);
    }
    public NoteFile must(int id) { return raw().must(id); }
    public byte[] download(int id) { return raw().download(id); }
    public void remove(int id) { raw().remove(id); }
    public String state(int id) { return raw().state(id); }

    public List<KanbanCardFile> listForCard(int cardId) { return raw().listForCard(cardId); }
    public KanbanCardFile addForCard(int cardId, String name, String ctype, byte[] data) {
        return raw().addForCard(cardId, name, ctype, data);
    }
    public KanbanCardFile mustCardFile(int id) { return raw().mustCardFile(id); }
    public byte[] downloadCardFile(int id) { return raw().downloadCardFile(id); }
    public void removeCardFile(int id) { raw().removeCardFile(id); }
    public String cardState(int id) { return raw().cardState(id); }

    void scheduleNoteDeletion(int noteId) { raw().scheduleNoteDeletion(noteId); }
    void scheduleCardDeletion(int cardId) { raw().scheduleCardDeletion(cardId); }
    void kick() { raw().kick(); }
}
