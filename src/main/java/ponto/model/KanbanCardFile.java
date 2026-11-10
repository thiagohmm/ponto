package ponto.model;

/** One file attached to a kanban card; the bytes live in S3, the row is the index. */
public record KanbanCardFile(int id, int cardId, String name, String key, long size, String ctype, String crea) { }
