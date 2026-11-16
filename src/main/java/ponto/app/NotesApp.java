package ponto.app;

import ponto.infra.NotesStore;
import ponto.model.Folder;
import ponto.model.Note;
import ponto.model.Tag;

import java.util.List;

/** Application API for markdown notes, folders and tags. */
public final class NotesApp {
    private final NotesStore store;

    NotesApp(NotesStore store) { this.store = store; }

    NotesStore store() { return store; }

    public List<Note> list(String q) { return store.list(q); }
    public Note byId(int id) { return store.byId(id); }
    public Note create(String title, String body) { return store.create(title, body); }
    public Note create(String title, String body, Integer folderId, List<Integer> tagIds) {
        return store.create(title, body, folderId, tagIds);
    }
    public Note update(int id, String title, String body, int revision) {
        return store.update(id, title, body, revision);
    }
    public Note move(int id, Integer folderId) { return store.move(id, folderId); }
    public void delete(int id) { store.delete(id); }
    public Note setTags(int id, List<Integer> tagIds) { return store.setTags(id, tagIds); }
    public Note addTag(int id, int tagId) { return store.addTag(id, tagId); }
    public List<Tag> tags() { return store.tags(); }
    public Tag createTag(String name, String color) { return store.createTag(name, color); }
    public Tag renameTag(int id, String name, String color) { return store.renameTag(id, name, color); }
    public void deleteTag(int id) { store.deleteTag(id); }
    public List<Folder> folders() { return store.folders(); }
    public Folder createFolder(String name, Integer parent) { return store.createFolder(name, parent); }
    public Folder renameFolder(int id, String name) { return store.renameFolder(id, name); }
    public Folder moveFolder(int id, Integer parent) { return store.moveFolder(id, parent); }
    public void deleteFolder(int id) { store.deleteFolder(id); }
}
