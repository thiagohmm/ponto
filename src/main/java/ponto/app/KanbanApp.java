package ponto.app;

import ponto.infra.KanbanStore;
import ponto.model.KanbanCard;
import ponto.model.KanbanColumn;

import java.util.List;

/** Application API for the per-company kanban board. */
public final class KanbanApp {
    private final KanbanStore store;

    KanbanApp(KanbanStore store) { this.store = store; }

    KanbanStore store() { return store; }

    public List<KanbanColumn> columns() { return store.columns(); }
    public List<KanbanCard> cards() { return store.cards(); }
    public KanbanColumn createColumn(String name) { return store.createColumn(name); }
    public KanbanColumn updateColumn(int id, String name, Integer position) {
        return store.updateColumn(id, name, position);
    }
    public void deleteColumn(int id) { store.deleteColumn(id); }
    public KanbanCard createCard(String title, String body, int columnId) {
        return store.createCard(title, body, columnId);
    }
    public KanbanCard updateCard(int id, String title, String body, String done, int revision) {
        return store.updateCard(id, title, body, done, revision);
    }
    public KanbanCard moveCard(int id, int columnId, int position) {
        return store.moveCard(id, columnId, position);
    }
    public KanbanCard blockCard(int id, String kind, String reason) {
        return store.blockCard(id, kind, reason);
    }
    public KanbanCard unblockCard(int id) { return store.unblockCard(id); }
    public KanbanCard byId(int id) { return store.byId(id); }
    public void deleteCard(int id) { store.deleteCard(id); }
}
