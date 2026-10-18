package ponto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Input parsing, pt-BR friendly. Each method returns a value or throws Bad with
 * a short message; the caller echoes it and asks again.
 */
final class Parse {

    private static final Pattern ISO    = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern DMY    = Pattern.compile("(\\d{1,2})[/\\-.](\\d{1,2})(?:[/\\-.](\\d{2,4}))?");
    private static final Pattern MONTH  = Pattern.compile("(\\d{1,2})[ \\-.](jan|feb|mar|abr|mai|jun|jul|ago|set|oct|nov|dec)(?:[ \\-.](\\d{2,4}))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern OFFSET = Pattern.compile("([+-])(\\d{1,3})");
    private static final Pattern DAY    = Pattern.compile("(\\d{1,2})");
    private static final Pattern TIME   = Pattern.compile("(\\d{1,2})[:.h]?(\\d{1,2})?[m]?([ap])?m?", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUM    = Pattern.compile("\\d{1,8}");

    private static final String[] MON = {"jan","feb","mar","abr","mai","jun","jul","ago","set","oct","nov","dec"};
    private static final java.util.List<String> MONS = java.util.List.of("jan","feb","mar","abr","mai","jun","jul","ago","set","oct","nov","dec");

    /**
     * A date: 08/10 · 08/10/2026 · 2026-10-08 · 5oct · today/h/hoje ·
     * yesterday/y/yer · -3 (3 days back) · +2 · or a bare day of this month.
     */
    static LocalDate date(String raw) throws Bad {
        String s = raw.strip().toLowerCase();
        LocalDate today = LocalDate.now();
        if (s.isEmpty()) throw new Bad("date required");

        if (s.equals("t") || s.equals("h") || s.equals("today") || s.equals("toda") || s.equals("hoje")) return today;
        if (s.equals("y") || s.equals("yesterday") || s.equals("ayer") || s.equals("yer")) return today.minusDays(1);

        Matcher off = OFFSET.matcher(s);
        if (off.matches()) {
            long n = digits(off.group(2));
            return off.group(1).equals("-") ? today.minusDays(n) : today.plusDays(n);
        }

        Matcher iso = ISO.matcher(s);
        if (iso.matches()) return day(num(iso.group(1)), num(iso.group(2)), num(iso.group(3)), s);

        Matcher mon = MONTH.matcher(s);
        if (mon.matches()) {
            int mo = MONS.indexOf(mon.group(2).toLowerCase()) + 1;
            return day(year(mon.group(3)), mo, num(mon.group(1)), s);
        }

        Matcher dmy = DMY.matcher(s);
        if (dmy.matches()) {
            int d = num(dmy.group(1)), mo = num(dmy.group(2));
            if (d < 1 || d > 31 || mo < 1 || mo > 12) throw bad(s);
            return day(year(dmy.group(3)), mo, d, s);
        }

        Matcher dd = DAY.matcher(s);
        if (dd.matches()) {
            int d = num(dd.group(1));
            if (d < 1 || d > today.lengthOfMonth()) throw bad(s);
            return LocalDate.of(today.getYear(), today.getMonth(), d);
        }

        throw bad(s);
    }

    /**
     * A time: 07:30 · 07.30 · 07h30 · 0730 · 8 · 8h · now/n · 7pm · 7:05am.
     */
    static LocalTime time(String raw) throws Bad {
        String s = raw.strip().toLowerCase();
        if (s.equals("now") || s.equals("n") || s.equals("rn")) return LocalTime.now().withSecond(0).withNano(0);

        Matcher m = TIME.matcher(s);
        if (!m.matches()) throw new Bad("time: '" + raw + "'  (07:30 · 07h30 · 0730 · 8 · now)");
        int h = num(m.group(1));
        String g2 = m.group(2);
        int mi = g2 == null || g2.isEmpty() ? 0 : num(g2);
        String mer = m.group(3);
        mer = mer == null ? "" : mer.toLowerCase();
        if (mi > 59 || h > 23 || (!mer.isEmpty() && h > 12)) throw bad2(raw);
        if (mer.equals("p") && h < 12) h += 12;
        if (mer.equals("a") && h == 12) h = 0;
        return LocalTime.of(h, mi);
    }

    /**
     * A day, month or year selection to list by: `2026` · `10/2026` · `2026-10` ·
     * `oct` · `oct 2026` · `octobre` · `01/10/2026` · `2026-10-08` · `today` ·
     * `y` · `-3` · `12` (a day of this month). The pattern runs against the ISO
     * day, so a bare year means "everything containing 2026".
     */
    record Like(String pattern, String label) { }

    static Like like(String raw) throws Bad {
        String s = raw.strip().toLowerCase();
        if (s.isEmpty()) throw new Bad("date: type 2026 · 10/2026 · oct · 01/10/2026");
        LocalDate today = LocalDate.now();

        if (TODAY.contains(s)) return new Like(Str.iso(today), Str.d(today));
        if (YESTER.contains(s)) { LocalDate y = today.minusDays(1); return new Like(Str.iso(y), Str.d(y)); }

        Matcher off = OFFSET.matcher(s);
        if (off.matches()) {
            long n = digits(off.group(2));
            LocalDate d = off.group(1).equals("-") ? today.minusDays(n) : today.plusDays(n);
            return new Like(Str.iso(d), Str.d(d));
        }

        if (YEAR.matcher(s).matches()) {                       // a whole year
            int y = (int) digits(s);
            if (y < 1900 || y > 2999) throw badRange(raw);
            return new Like("%" + y + "%", "" + y);
        }

        Matcher ym = YM.matcher(s);                            // 10/2026 · 2026-10
        if (ym.matches()) {
            boolean iso = ym.group(3) != null;                  // "2026-10", not "10/2026"
            int y  = iso ? (int) digits(ym.group(3)) : (int) year(ym.group(2));
            int mo = iso ? (int) digits(ym.group(4)) : (int) digits(ym.group(1));
            if (mo < 1 || mo > 12) throw badRange(raw);
            return new Like(String.format("%d-%02d%%", y, mo), MON[mo - 1] + " " + y);
        }

        Matcher nm = MONTH_NAME.matcher(s);                    // oct · outubro · oct 2026
        if (nm.matches()) {
            Integer mo = ALIAS.get(nm.group(1));
            if (mo == null) throw badRange(raw);
            return new Like(String.format("%d-%02d%%", (int) year(nm.group(2)), mo),
                            MON[mo - 1] + " " + (int) year(nm.group(2)));
        }

        LocalDate d = date(s);                                 // one day: 01/10/2026 · 5oct · 12
        return new Like(Str.iso(d), Str.d(d));
    }

    private static final Pattern YEAR       = Pattern.compile("\\d{4}");
    private static final Pattern YM         = Pattern.compile("(\\d{1,2})[/\\-.](\\d{4})|(\\d{4})[/\\-.](\\d{1,2})");
    private static final Pattern MONTH_NAME = Pattern.compile(
        "(jan|january|feb|february|mar|march|abr|april|apr|mai|may|jun|june|jul|july|ago|aug|august"
        + "|set|sep|september|oct|october|nov|november|dec|december)[ ./-]*(\\d{2,4})?",
        Pattern.CASE_INSENSITIVE);
    private static final java.util.Set<String> TODAY  = java.util.Set.of("t", "h", "today", "toda", "hoje");
    private static final java.util.Set<String> YESTER = java.util.Set.of("y", "yesterday", "ayer", "yer");
    private static final java.util.Map<String, Integer> ALIAS = java.util.Map.ofEntries(
        java.util.Map.entry("jan", 1), java.util.Map.entry("january", 1),
        java.util.Map.entry("feb", 2), java.util.Map.entry("february", 2),
        java.util.Map.entry("mar", 3), java.util.Map.entry("march", 3),
        java.util.Map.entry("abr", 4), java.util.Map.entry("apr", 4), java.util.Map.entry("april", 4),
        java.util.Map.entry("mai", 5), java.util.Map.entry("may", 5),
        java.util.Map.entry("jun", 6), java.util.Map.entry("june", 6),
        java.util.Map.entry("jul", 7), java.util.Map.entry("july", 7),
        java.util.Map.entry("ago", 8), java.util.Map.entry("aug", 8), java.util.Map.entry("august", 8),
        java.util.Map.entry("set", 9), java.util.Map.entry("sep", 9), java.util.Map.entry("september", 9),
        java.util.Map.entry("oct", 10), java.util.Map.entry("october", 10),
        java.util.Map.entry("nov", 11), java.util.Map.entry("november", 11),
        java.util.Map.entry("dec", 12), java.util.Map.entry("december", 12));

    /** A positive integer (id, how many rows). */
    static int num(String raw, String what) throws Bad {
        String s = raw.strip();
        if (!NUM.matcher(s).matches()) throw new Bad(what + ": '" + raw + "'");
        int v = (int) digits(s);
        if (v <= 0) throw new Bad(what + ": must be 1 or more");
        return v;
    }

    /** Plain number for the loose case (never negative-checked). */
    private static int num(String digitsOnly) { return (int) digits(digitsOnly); }

    /** Free text: only trimmed, never rejected. */
    static String text(String raw) { return raw.strip(); }

    // ---- helpers ---------------------------------------------------------

    private static Bad bad(String s) {
        return new Bad("date: '" + s + "'  (dd/mm · dd/mm/aaaa · aaaa-mm-dd · 5oct · today · y · -3)");
    }

    private static Bad bad2(String raw) { return new Bad("time: '" + raw + "' out of range"); }

    /** A date-shaped value whose month or year is impossible. */
    private static Bad badRange(String raw) { return new Bad("date: '" + raw + "'  (2026 · 10/2026 · oct · 01/10/2026)"); }

    private static LocalDate day(long y, int mo, int d, String src) throws Bad {
        try { return LocalDate.of((int) y, mo, d); }
        catch (RuntimeException ignored) { throw bad(src); }
    }

    /** "26" -> 2026; empty/null -> this year. */
    private static long year(String g) {
        if (g == null || g.isEmpty()) return LocalDate.now().getYear();
        int y = (int) digits(g);
        return y < 100 ? 2000 + y : y;
    }

    private static long digits(String s) {
        long v = 0;
        for (char c : s.toCharArray()) {
            if (c < '0' || c > '9') throw new Bad("number: '" + s + "'");
            v = v * 10 + (c - '0');
        }
        return v;
    }
}
