package ponto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One timesheet line: a day, an optional in/out pair and a justification.
 * Every field is freely editable / deletable by the user (blank time = no punch).
 */
public record Entry(int id, LocalDate dia, LocalTime tin, LocalTime tout, String just, String crea) {

    /** True when the line has no punch at all (just a justification). */
    public boolean empty() { return tin == null && tout == null; }

    /** True when only one side of the pair exists (open line). */
    public boolean open() { return (tin == null) != (tout == null); }

    /** True when both times exist. */
    public boolean closed() { return tin != null && tout != null; }

    /**
     * Duration in minutes. out < in is treated as an overnight span
     * (validated at input time). -1 when no full pair.
     */
    public long min() {
        if (!closed()) return -1;
        int a = tin.getHour() * 60 + tin.getMinute();
        int b = tout.getHour() * 60 + tout.getMinute();
        if (b <= a) b += 24 * 60;
        return b - a;
    }

    /** Duration as "h:mm" ("—" when there is no full pair). */
    public String dur() { return Dur.hm(min()); }

    /** Duration as decimal hours ("0.75"), empty when no full pair. */
    public double hours() { double m = min(); return m < 0 ? 0 : m / 60.0; }

    /** The duration column: "1:30", "open" (half line) or "note" (no punch). */
    public String durCol() { return closed() ? dur() : open() ? "open" : "note"; }

    /** The decimal hours column: blank unless the pair is complete. */
    public String hoursCol() { return closed() ? Str.n(hours()) : ""; }

    public String stamp() { return Str.t(tin) + "-" + Str.t(tout); }
}
