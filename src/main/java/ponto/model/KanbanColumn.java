package ponto.model;

/** One editable column on a company's kanban board. */
public record KanbanColumn(int id, String name, int position, String crea) { }
