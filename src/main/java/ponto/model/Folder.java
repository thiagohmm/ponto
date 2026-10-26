package ponto.model;

/** A notes folder. parent == null is the vault root. */
public record Folder(int id, Integer parent, String name, String crea) { }
