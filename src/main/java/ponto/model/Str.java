package ponto.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;

/** Small text/number formatting helpers (pt-BR, ASCII-safe widths). */
public final class Str {

    static final String[] D3 = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};
    static final String[] M3 = {"jan", "feb", "mar", "abr", "mai", "jun",
                                "jul", "ago", "set", "oct", "nov", "dec"};
    static final String PAD = "                                                  ";

    /** A time or an empty string when null. */
    public static String t(LocalTime v) { return v == null ? "" : p2(v.getHour()) + ":" + p2(v.getMinute()); }

    /** A date as dd/mm/yyyy. */
    public static String d(LocalDate v) {
        return v == null ? "" : p2(v.getDayOfMonth()) + "/" + p2(v.getMonthValue()) + "/" + v.getYear();
    }

    /** A date as dd mmm (e.g. "05 oct"). */
    static String dm(LocalDate v) {
        return v == null ? "" : p2(v.getDayOfMonth()) + " " + M3[v.getMonthValue() - 1];
    }

    /** ISO yyyy-mm-dd (the storage format). */
    public static String iso(LocalDate v) {
        return v == null ? "" : p4(v.getYear()) + "-" + p2(v.getMonthValue()) + "-" + p2(v.getDayOfMonth());
    }

    /** Month label "oct/2026". */
    static String mo(LocalDate v) {
        return v == null ? "" : M3[v.getMonthValue() - 1] + "/" + v.getYear();
    }

    /** Weekday from the ISO date string stored in the db. */
    static String d3(String iso) {
        try { return D3[LocalDate.parse(iso).getDayOfWeek().getValue() % 7]; }
        catch (RuntimeException ignored) { return ""; }
    }

    public static String d3(LocalDate v) { return v == null ? "" : D3[v.getDayOfWeek().getValue() % 7]; }

    static Month month(int oneBased) { return Month.of(oneBased); }

    /** Any number without locale surprises (ids, counts). */
    public static String s(long v) { return "" + v; }

    /** Decimal hours, always with a dot. */
    public static String n(double v) { return String.format(java.util.Locale.ROOT, "%.2f", v); }

    /** Minutes since midnight. */
    static int tod(LocalTime v) { return v.getHour() * 60 + v.getMinute(); }

    private static String p2(int v) { return v < 10 ? "0" + v : "" + v; }
    private static String p4(int v) { return v < 10 ? "000" + v : v < 100 ? "00" + v : v < 1000 ? "0" + v : "" + v; }

    /** Right-padded cell. */
    public static String fix(String s, int w) { return s.length() >= w ? s : s + PAD.substring(0, w - s.length()); }

    /** Left-padded cell. */
    static String rfix(String s, int w) { return s.length() >= w ? s : PAD.substring(0, w - s.length()) + s; }

    /** Cut to width, marking overflow with an ellipsis. */
    public static String cut(String s, int w) { return s.length() <= w ? s : s.substring(0, Math.max(0, w - 1)) + "…"; }

    /**
     * The overflow of a cut cell: the whole text, word-wrapped so a line never
     * exceeds `width` (a word longer than the whole line goes alone, hard-cut).
     */
    public static List<String> wrap(String s, int width) {
        List<String> out = new ArrayList<>();
        String line = "";
        for (String w : s.trim().split("\\s+")) {
            if (w.isEmpty()) continue;
            if (w.length() > width) {  // a word wider than the whole line: hard split
                if (!line.isEmpty()) { out.add(line); line = ""; }
                for (int i = 0; i < w.length(); i += width)
                    out.add(w.substring(i, Math.min(w.length(), i + width)));
                continue;
            }
            int need = line.isEmpty() ? w.length() : line.length() + 1 + w.length();
            if (need <= width) line += line.isEmpty() ? w : " " + w;
            else { out.add(line); line = w; }
        }
        if (!line.isEmpty()) out.add(line);
        return out;
    }

    /** Capitalise the first letter. */
    static String up(String s) { return s.isEmpty() ? s : s.substring(0, 1).toUpperCase() + s.substring(1); }
}
