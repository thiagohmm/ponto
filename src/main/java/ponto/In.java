package ponto;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Keyboard input. Every prompt is one short label plus the current value in
 * brackets: Enter keeps it, "-"/"x" clears it, anything else replaces it.
 */
final class In {

    /** raw text typed, plus whether the user just pressed Enter (keep). */
    record Val(String raw, boolean keep) {
        /** clear marker: "-" or "x". */
        boolean clear() { return raw.equals("-") || raw.equalsIgnoreCase("x"); }
        /** the effective string ("" when cleared or when the current value was empty). */
        String value(String cur) { return keep ? cur : clear() ? "" : raw; }
    }

    private final Out o;
    /** One plain line reader on stdin: works in a tty and when piped. */
    private final BufferedReader rd =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    In(Out o) { this.o = o; }

    private String read() {
        String s;
        try {
            s = rd.readLine();
        } catch (java.io.IOException e) { throw new Problema("input: " + e.getMessage()); }
        if (s == null) throw new Eof();
        return s.stripTrailing();
    }

    /** Menu key + optional args on the same line ("2 12", "3 5 6 7", "4 40"). */
    Cmd cmd() {
        o.key();
        String s = read();
        String[] t = s.strip().split("\\s+");
        String key = t[0].isEmpty() ? "" : t[0].toLowerCase();
        int arg = 0;
        List<Integer> ids = new ArrayList<>();
        for (int i = 1; i < t.length; i++) {
            Integer v = safe(t[i]);
            if (v != null) ids.add(v);
        }
        if (!ids.isEmpty()) arg = ids.getFirst();
        return new Cmd(key, arg, ids, s.strip().substring(key.length()).strip());
    }

    record Cmd(String key, int arg, List<Integer> ids, String rest) { }

    private static Integer safe(String s) {
        try { return s.chars().allMatch(c -> c >= '0' && c <= '9') && !s.isEmpty() ? Integer.parseInt(s) : null; }
        catch (RuntimeException ignored) { return null; }
    }

    /** Free text, never rejected. */
    String text(String label) { o.ask(label); return read().strip(); }

    /** Text with default (Enter keeps the default). */
    String text(String label, String def) {
        o.ask(label, def);
        String s = read().strip();
        return s.isEmpty() ? def : s;
    }

    /** A required line: repeats until something is typed. */
    String must(String label) {
        for (;;) {
            o.ask(label);
            String s = read().strip();
            if (!s.isEmpty()) return s;
            o.bad("required");
        }
    }

    /** Editable field of an existing record. */
    Val field(String label, String cur) {
        o.ask(label, cur);
        String s = read().strip();
        return new Val(s, s.isEmpty());
    }

    /** Positive integer with retry; blank keeps the default (or re-prompts). */
    int num(String label, int def) {
        o.ask(label, def <= 0 ? null : "" + def);
        String s = read().strip();
        if (s.isEmpty()) {
            if (def > 0) return def;
            o.bad("required");
            return num(label, def);
        }
        try { return Parse.num(s, label); }
        catch (Bad e) { o.bad(e.getMessage()); return num(label, def); }
    }

    /** Date with default; blank keeps the default. */
    LocalDate date(String label, LocalDate def) {
        for (;;) {
            o.ask(label, def == null ? null : Str.d(def));
            String s = read().strip();
            if (s.isEmpty()) {
                if (def == null) { o.bad("date required"); continue; }
                return def;
            }
            try { return Parse.date(s); }
            catch (Bad e) { o.bad(e.getMessage()); }
        }
    }

    /**
     * Optional time. Enter keeps the default (which may itself be empty = no
     * punch); "now"/"n" = current minute; "-" clears it.
     */
    Optional<LocalTime> time(String label, String def) {
        o.ask(label, def == null ? "" : def);
        String s = read().strip();
        if (s.isEmpty() || s.equals("-") || s.equalsIgnoreCase("x")) {
            if (s.isEmpty()) return def == null || def.isEmpty()
                    ? Optional.<LocalTime>empty() : parse(label, def);
            return Optional.<LocalTime>empty();
        }
        return parse(label, s);
    }

    private Optional<LocalTime> parse(String label, String s) {
        try { return Optional.of(Parse.time(s)); }
        catch (Bad e) { o.bad(e.getMessage()); return time(label, s); }
    }

    /** Yes/No, default encoded in the bracket. */
    boolean yes(String label, boolean def) {
        o.ask(label, def ? "Y/n" : "y/N");
        String s = read().strip().toLowerCase();
        if (s.isEmpty()) return def;
        if (s.charAt(0) == 'y' || s.charAt(0) == 's' || s.equals("1")) return true;
        if (s.charAt(0) == 'n' || s.charAt(0) == 'o' || s.equals("0")) return false;
        o.bad("y or n");
        return yes(label, def);
    }

    /** Pick one letter: `opts` "ai" prints [a/i]; Enter keeps `def`. */
    char choice(String label, String opts, char def) {
        String hint = "";
        for (int i = 0; i < opts.length(); i++) hint += (i > 0 ? "/" : "") + opts.charAt(i);
        for (;;) {
            o.ask(label, hint);
            String s = read().strip().toLowerCase();
            if (s.isEmpty()) return def;
            char c = s.charAt(0);
            if (opts.indexOf(c) >= 0) return c;
            o.bad("one of " + hint);
        }
    }

    /** Period as "a b" / "a..b" / "a" (single day) / blank (default range). */
    Period period(String label, LocalDate lo, LocalDate hi) {
        for (;;) {
            String def = lo == null || hi == null ? "" : Str.d(lo) + " " + Str.d(hi);
            o.ask(label, def);
            String s = read().strip();
            if (s.isEmpty()) {
                if (lo == null || hi == null) { o.bad("period required"); continue; }
                return new Period(lo, hi);
            }
            String[] t = split(s);
            try {
                LocalDate a = Parse.date(t[0]);
                LocalDate b = t.length > 1 ? Parse.date(t[1]) : a;
                if (b.isBefore(a)) { LocalDate x = a; a = b; b = x; }
                return new Period(a, b);
            } catch (Bad e) { o.bad(e.getMessage()); }
        }
    }

    /** Id(s) on one line: "12", "5 6 7". Blank keeps the default. */
    List<Integer> ids(String label, int def) {
        o.ask(label, def <= 0 ? null : "" + def);
        String s = read().strip();
        if (s.isEmpty()) {
            if (def > 0) return List.of(def);
            o.bad("required");
            return ids(label, def);
        }
        List<Integer> out = new ArrayList<>();
        for (String t : s.split("\\s+")) {
            Integer v = safe(t);
            if (v == null || v <= 0) { o.bad("id(s): " + s); return ids(label, def); }
            out.add(v);
        }
        if (out.isEmpty()) { o.bad("id(s): " + s); return ids(label, def); }
        return out;
    }

    private static String[] split(String s) {
        String[] t = s.replace("..", " ").replace("...", " ").replace(" to ", " ").split("\\s+");
        return t.length == 0 ? new String[]{"", ""} : t.length == 1 ? new String[]{t[0], t[0]} : t;
    }
}

/** A closed date range. */
record Period(LocalDate from, LocalDate to) {
    boolean has(LocalDate d) { return !d.isBefore(from) && !d.isAfter(to); }
    long days() { return from.datesUntil(to.plusDays(1)).count(); }
    String label() { return Str.d(from) + " " + Str.d(to); }
}
