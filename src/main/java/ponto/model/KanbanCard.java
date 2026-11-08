package ponto.model;

/** One card on a company's kanban board. Independent of markdown notes. */
public record KanbanCard(int id, int columnId, String title, String body, int position,
                         int revision, String crea, String upd) { }
