package ponto.model;

/** One workplace: own punches, notes, day-off list and workday length. */
public record Company(int id, String name, String off, String daily, String crea) { }
