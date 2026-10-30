package ponto.model;

/** One file attached to a note; the bytes live in S3, the row is the index. */
public record NoteFile(int id, int noteId, String name, String key, long size, String ctype, String crea) { }
