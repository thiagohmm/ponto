package ponto.view;

import ponto.model.Entry;
import ponto.model.Str;

/** Columns of the entry tables: the duration/decimal-hours pair as rendered everywhere. */
public final class Cols {

    /** The duration column: "1:30", "open" (half line) or "note" (no punch). */
    public static String dur(Entry e) { return e.closed() ? e.dur() : e.open() ? "open" : "note"; }

    /** The decimal hours column: blank unless the pair is complete. */
    public static String hours(Entry e) { return e.closed() ? Str.n(e.hours()) : ""; }
}
