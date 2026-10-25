package ponto.model;

import java.time.LocalDate;

/** A closed date range. */
public record Period(LocalDate from, LocalDate to) {
    public boolean has(LocalDate d) { return !d.isBefore(from) && !d.isAfter(to); }
    public long days() { return from.datesUntil(to.plusDays(1)).count(); }
    public String label() { return Str.d(from) + " " + Str.d(to); }
}
