package ponto.model;

/** One free-form markdown note. Independent of the timesheet. */
public record Note(int id, String title, String body, String crea, String upd, Integer folderId) { }
