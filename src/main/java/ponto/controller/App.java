package ponto.controller;

import ponto.Bad;
import ponto.Eof;
import ponto.Problema;
import ponto.Ver;
import ponto.model.Dur;
import ponto.model.Entry;
import ponto.model.Holidays;
import ponto.model.Parse;
import ponto.model.Period;
import ponto.model.Report;
import ponto.model.Service;
import ponto.model.Str;
import ponto.view.Cols;
import ponto.view.Csv;
import ponto.view.In;
import ponto.view.Out;
import ponto.view.Pdf;
import ponto.view.Xls;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The whole program: one compact menu, one key per action.
 * Nothing ever has to be typed as "ponto in <date> <time> <justification>" —
 * keys are single characters and the current value is always the default.
 */
public final class App {

    private final Service svc;
    private final Out out = new Out();
    private final In in;

    public App(Service svc) { this.svc = svc; this.in = new In(out); }

    public void run() {
        setup();
        svc.registerOff(s -> out.warn("off: '" + Str.cut(s, 24) + "'   ·   not a date, ignored"));
        for (;;) {
            home();
            try {
                In.Cmd c = in.cmd();
                if (c.key().isEmpty()) continue;
                if (c.key().equals("0") || c.key().equals("q")) { out.blank(); return; }
                dispatch(c);
            } catch (Eof ignored) {
                out.blank();
                return;
            } catch (Bad e) {
                out.bad(e.getMessage());
            } catch (Problema e) {
                out.bad(e.getMessage());
            }
        }
    }

    private void dispatch(In.Cmd c) {
        switch (c.key()) {
            case "1" -> novo();
            case "2" -> edit(c.arg());
            case "3" -> del(c);
            case "4" -> list(c);
            case "5" -> sum();
            case "6" -> find(c.rest());
            case "7" -> export("pdf");
            case "8" -> export("xls");
            case "9" -> export("csv");
            case "c" -> config();
            case "d" -> byDate(c.rest());
            case "x" -> out.clear();
            case "?" -> help();
            default -> out.bad("unknown key '" + c.key() + "'   ·   ? help");
        }
    }

    private void home() {
        Map<String, Object> h = svc.home();
        long mins = (Long) h.get("monthMins");
        int records = (Integer) h.get("records");
        int open = (Integer) h.get("open");
        String since = (String) h.get("openSince");
        String openNote = open == 0 ? "open 0"
                : "open " + open + (since.isEmpty() ? "" : "   since " + since);
        out.home((String) h.get("who"), records, mins, (String) h.get("db"), openNote);
    }

    private void setup() {
        if (svc.asked()) return;
        if (!Out.TTY) { svc.markAsked(); return; }
        out.title("SET UP");
        out.note("db    " + svc.dbPath());
        String who = in.text("name", svc.rawWho());
        svc.setConfig(who, null, false);
        if (!who.isEmpty()) out.ok("user " + who);
    }

    private void emptyCheck() {
        if (svc.count() == 0) throw new Problema("no records yet (1 = new)");
    }

    // ---- 1 new -----------------------------------------------------------

    private void novo() {
        out.title("NEW");
        LocalDate dia = in.date("day", LocalDate.now());
        offDay(dia, true);
        List<Entry> same = svc.on(dia);

        // the day you picked, as it stands right now: every line already on it
        if (!same.isEmpty()) {
            out.note(Str.d(dia) + "   " + same.size() + " line(s) already on this day");
            table(same);
            totalLine(minsOf(same), same.size(), openOf(same));
        }

        // a half-open line on that day is normally the one you want to close
        List<Entry> open = same.stream().filter(e -> e.tin() != null && e.tout() == null).toList();
        if (open.size() == 1) {
            Entry o = open.getFirst();
            out.warn("open: #" + o.id() + "   in " + Str.t(o.tin()));
            if (in.yes("close #" + o.id() + "?", true)) {
                Optional<LocalTime> t = in.time("out", Str.t(LocalTime.now()));
                if (t.isPresent()) {
                    Entry done = new Entry(o.id(), o.dia(), o.tin(), t.get(), o.just(), o.crea());
                    check(done, done.id());
                    Entry saved = svc.update(done.id(), done.dia(), done.tin(), done.tout(), done.just(), true, true);
                    out.ok("#" + done.id() + "  " + Str.d(dia) + " " + saved.stamp() + "  " + saved.dur());
                    return;
                }
            }
        }

        Optional<LocalTime> tin = in.time("in", lastOut(same));
        Optional<LocalTime> tout = in.time("out", punchNow(tin, dia));
        String just = in.text("just", "");

        if (tin.isEmpty() && tout.isEmpty() && just.isEmpty()) { out.warn("nothing typed"); return; }
        if (tout.isPresent() && tin.isEmpty() && !open.isEmpty()) {
            out.warn("#" + open.getFirst().id() + " is already open with the same day");
        }
        Entry fresh = new Entry(0, dia, tin.orElse(null), tout.orElse(null), just, "");
        check(fresh, 0);

        Entry made = svc.create(dia, fresh.tin(), fresh.tout(), just, false, true, true);
        out.ok("#" + made.id() + "  " + Str.d(dia) + "  " + made.stamp() + "  " + made.dur());
        if (made.open()) out.warn("half line: fill the other time later (2 = edit)");
    }

    /** Default for "out": now, but only when starting a punch today. */
    private String punchNow(Optional<LocalTime> tin, LocalDate dia) {
        return tin.isPresent() && dia.equals(LocalDate.now()) ? Str.t(LocalTime.now()) : "";
    }

    /** Default for the next "in": the latest "out" of that day. */
    private String lastOut(List<Entry> same) {
        LocalTime best = null;
        for (Entry e : same) if (e.tout() != null && (best == null || e.tout().isAfter(best))) best = e.tout();
        return best == null ? "" : Str.t(best);
    }

    // ---- 2 edit ----------------------------------------------------------

    private void edit(int id) {
        out.title("EDIT");
        if (id <= 0) table(svc.recent(12, 0));
        Entry e = svc.byId(id > 0 ? id : in.num("id", 0));

        out.note("#" + e.id() + "  " + Str.d(e.dia()) + "  " + e.stamp() + "  " + Str.cut(e.just(), 44));
        out.note("Enter keeps   ·   - clears");

        LocalDate dia = patchDate(in.field("day", Str.d(e.dia())), e.dia());
        offDay(dia, !dia.equals(e.dia()));
        LocalTime tin = patchTime(in.field("in", Str.t(e.tin())), e.tin());
        LocalTime tout = patchTime(in.field("out", Str.t(e.tout())), e.tout());
        String just = patchText(in.field("just", e.just()), e.just());

        Entry now = new Entry(e.id(), dia, tin, tout, just, e.crea());
        if (now.open() && now.just().isEmpty()) throw new Bad("a half line needs a justification");
        if (now.closed() && now.min() == 0) throw new Bad("in = out");
        check(now, now.id());

        if (same(e, now)) { out.note("unchanged"); return; }
        Entry saved = svc.update(now.id(), dia, tin, tout, just, true, true);
        out.done(true, "#" + now.id() + "  " + Str.d(dia) + "  " + saved.stamp() + "  " + saved.dur());
    }

    /**
     * A weekend or a day off is worth one line of yellow, not a wall: people
     * do work on those days and a lost punch costs more than a wrong one.
     * `force` is false when the day was already there and nobody moved it.
     */
    private void offDay(LocalDate d, boolean force) {
        if (!force) return;
        String off = Holidays.off(d);
        if (!off.isEmpty()) out.warn(Str.d(d) + "  " + Str.d3(d) + "   " + off);
    }

    private LocalDate patchDate(In.Val v, LocalDate cur) {
        for (;;) {
            if (v.keep()) return cur;
            try { return Parse.date(v.raw()); }
            catch (Bad b) { out.bad(b.getMessage()); v = in.field("day", Str.d(cur)); }
        }
    }

    private LocalTime patchTime(In.Val v, LocalTime cur) {
        for (;;) {
            if (v.keep()) return cur;
            if (v.clear()) return null;
            try { return Parse.time(v.raw()); }
            catch (Bad b) { out.bad(b.getMessage()); v = in.field("time", Str.t(cur)); }
        }
    }

    private String patchText(In.Val v, String cur) { return v.keep() ? cur : v.value(cur); }

    private static boolean same(Entry a, Entry b) {
        return a.dia().equals(b.dia()) && a.tin() == b.tin() && a.tout() == b.tout() && a.just().equals(b.just());
    }

    /** Midnight + overlap guard (shared by new and edit; `skip` excludes the row itself). */
    private void check(Entry e, int skip) {
        Service.Warn w = svc.inspect(e, skip);
        if (w.sameTime()) throw new Bad("in = out");
        if (w.midnight() && !in.yes("crosses midnight (" + w.midnightLabel() + ")?", true))
            throw new Problema("cancelled");
        if (w.overlap() != null) {
            Entry o = w.overlap();
            if (!in.yes("overlaps #" + o.id() + " " + o.stamp() + " (" + o.dur() + ")?", false))
                throw new Problema("cancelled");
        }
    }

    // ---- 3 delete --------------------------------------------------------

    /**
     * Two ways to delete, asked up front: a whole day or specific ids. Either way
     * the rows are shown with their total, confirmed once, and the menu comes back.
     *
     *   3              asks: all day or id
     *   3 7          3 5 6 8        ids straight away
     *   3 01/10      3 a          a whole day
     */
    private void del(In.Cmd c) {
        out.title("DELETE");
        if (!c.ids().isEmpty()) { delIds(c.ids()); return; }

        String rest = c.rest().toLowerCase();
        if (rest.startsWith("a") || rest.startsWith("d")) { delDay(in.date("day", defaultDay())); return; }
        LocalDate d = null;
        if (!rest.isEmpty()) { try { d = Parse.date(rest); } catch (Bad ignored) { } }
        if (d != null) { delDay(d); return; }

        if (in.choice("all day or id", "ai", 'a') == 'a') delDay(in.date("day", defaultDay()));
        else delIds(in.ids("id(s)", 0));
    }

    /** A whole day at once: every line of it goes, nothing else is touched. */
    private void delDay(LocalDate d) {
        List<Entry> rows = svc.on(d);
        if (rows.isEmpty()) { out.note("nothing on " + Str.d(d)); return; }
        out.note(Str.d(d) + "   " + rows.size() + " line(s)");
        table(rows);
        totalLine(minsOf(rows), rows.size(), openOf(rows));
        if (!in.yes("delete all " + rows.size() + " of " + Str.d(d) + "?", false)) { out.note("cancelled"); return; }
        int n = svc.deleteDay(d);
        out.ok("deleted " + n + "   " + Str.d(d) + "   day is empty now");
    }

    /** One or many ids; the ones that are not found are named, the rest go. */
    private void delIds(List<Integer> ids) {
        List<Entry> hits = new ArrayList<>();
        List<Integer> missing = new ArrayList<>();
        for (int id : ids) {
            try { hits.add(svc.byId(id)); } catch (Problema ignored) { missing.add(id); }
        }
        if (hits.isEmpty()) { out.bad("no record: " + ids); return; }
        table(hits);
        totalLine(minsOf(hits), hits.size(), openOf(hits));
        if (!in.yes("delete " + hits.size() + " line(s)?", false)) { out.note("cancelled"); return; }
        for (Entry h : hits) svc.delete(h.id());
        out.ok("deleted " + hits.size() + (missing.isEmpty() ? "" : "   not found: " + missing));
    }

    /** Today if it has lines, otherwise the last day that has any. */
    private LocalDate defaultDay() {
        LocalDate today = LocalDate.now();
        return svc.countOn(today) > 0 ? today : nzD(svc.max(), today);
    }

    private static LocalDate nzD(LocalDate v, LocalDate def) { return v == null ? def : v; }

    /** Hours of the closed lines only (half lines and notes give 0). */
    private static long minsOf(List<Entry> rows) {
        long m = 0;
        for (Entry e : rows) if (e.min() > 0) m += e.min();
        return m;
    }

    /** Lines without a complete pair. */
    private static int openOf(List<Entry> rows) {
        int n = 0;
        for (Entry e : rows) if (!e.closed()) n++;
        return n;
    }

    // ---- 4 list / d by date / 6 find ----------------------------------

    /**
     * Paging by offset, never by "id < the smallest id shown": the table is ordered
     * by day, so an id cursor can skip rows for good. `id DESC` as the last sort key
     * makes the order total, so `older` walks every record exactly once.
     */
    private void page(int total, int size, java.util.function.BiFunction<Integer, Integer, List<Entry>> take) {
        int shown = 0;
        long mins = 0;
        int half = 0;
        for (;;) {
            List<Entry> rows = take.apply(size, shown);
            if (rows.isEmpty()) return;
            table(rows);
            shown += rows.size();
            mins += minsOf(rows); half += openOf(rows);
            // running total of the rows above: half lines and notes count as 0
            totalLine(mins, shown, half);
            out.note("shown " + shown + " of " + total);
            if (shown >= total || !in.yes("older", false)) return;
        }
    }

    /**
     * `4` the newest records · `4 50` how many · `4 2026`, `4 10/2026`,
     * `4 oct`, `4 01/10/2026` every record of that year, month or day.
     */
    private void list(In.Cmd c) {
        out.title("LIST");
        String rest = c.rest().strip();
        if (rest.isEmpty()) { page(svc.count(), c.arg() > 0 ? c.arg() : 20, (n, s) -> svc.recent(n, s)); return; }
        if (countOnly(rest)) { page(svc.count(), Integer.parseInt(rest), (n, s) -> svc.recent(n, s)); return; }
        Parse.Like f;
        try { f = Parse.like(rest); } catch (Bad e) { out.bad(e.getMessage()); return; }
        byFilter(f);
    }

    /** `d` — all the records of a day, month or year, asked for. */
    private void byDate(String rest) {
        out.title("BY DATE");
        String ask = rest.strip();
        if (ask.isEmpty()) ask = in.text("day, month or year", "");
        if (ask.isEmpty()) { out.note("type 2026 · 10/2026 · oct · 01/10/2026"); return; }
        Parse.Like f;
        try { f = Parse.like(ask); } catch (Bad e) { out.bad(e.getMessage()); return; }
        byFilter(f);
    }

    /** Everything whose day matches the pattern, paged, with the running total. */
    private void byFilter(Parse.Like f) {
        int total = svc.countDay(f.pattern());
        out.note("matching  " + f.label() + "   ·   " + total + " record(s)");
        if (total == 0) { out.note("nothing in " + f.label()); return; }
        page(total, 20, (n, s) -> svc.byDay(f.pattern(), n, s));
    }

    /** A bare 1..999 after `4` means "how many rows"; a 4-digit year is a date. */
    private static boolean countOnly(String s) {
        if (s.isEmpty() || s.length() > 3 || !s.chars().allMatch(ch -> ch >= '0' && ch <= '9')) return false;
        return Integer.parseInt(s) >= 1;
    }

    /** One total line: "h:mm" and decimal hours, the same pair the reports print. */
    private void totalLine(long mins, int lines, int half) {
        out.note("total  " + Dur.hm(mins) + "   " + Str.n(mins / 60.0) + " h   ·   " + lines + " line(s)"
                 + (half > 0 ? "   ·   " + half + " incomplete, not counted" : ""));
    }

    private void find(String rest) {
        out.title("FIND");
        String q = rest.isEmpty() ? in.text("text", "") : rest;
        if (q.isEmpty()) { out.note("type some text"); return; }
        List<Entry> rows = svc.search(q);
        if (rows.isEmpty()) { out.note("no match for '" + q + "'"); return; }
        table(rows);
        out.note("'" + q + "' -> " + rows.size() + " lines  ·  " + Dur.hm(totalOf(rows)));
    }

    private static final int JUST_W = 34;   // justification column; the full text follows below

    private void table(List<Entry> rows) {
        out.note(cols("ID", "DATE", "DAY", "IN", "OUT", "DUR", "JUSTIFICATION"));
        out.note("-".repeat(76));
        for (Entry e : rows) {
            out.note(cols("" + e.id(), Str.d(e.dia()), Str.d3(e.dia()), Str.t(e.tin()), Str.t(e.tout()),
                         Cols.dur(e), Str.cut(e.just(), JUST_W)));
            // the table column is narrow, but the text must never be lost: every
            // cut justification follows its line, word-wrapped ("· " = "continued")
            if (e.just().length() > JUST_W)
                for (String l : Str.wrap(e.just(), 74)) out.note("· " + l);
        }
    }

    private static String cols(String... v) {
        int[] w = {5, 11, 4, 6, 6, 7, JUST_W};
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length; i++) b.append(i > 0 ? "  " : "").append(Str.fix(v[i], w[i]));
        return b.toString();
    }

    // ---- 5 sum -----------------------------------------------------------

    private void sum() {
        out.title("SUM");
        emptyCheck();
        Period p = in.period("period", svc.min(), svc.max());
        Report r = svc.report(p.from(), p.to());
        out.note(Str.fix("DATE", 12) + Str.fix("DAY", 6) + Str.fix("LINES", 8) + Str.fix("DURATION", 12));
        out.note("-".repeat(38));
        for (Report.Day d : r.summary()) {
            out.note(Str.fix(Str.d(d.dia()), 12) + Str.fix(d.d3(), 6) + Str.fix("" + d.lines(), 8) + Str.fix(Dur.hm(d.mins()), 12));
        }
        out.note("-".repeat(38));
        out.note(boldRow("TOTAL", r));
        if (r.openLines() > 0) out.warn(r.openLines() + " incomplete line(s) not counted");
    }

    private String boldRow(String label, Report r) {
        return Str.fix(label, 12) + Str.fix("" + r.lines().size(), 6) + Str.fix(r.totalLabel(), 8)
             + Str.fix(r.totalDec() + " h", 12);
    }

    private long totalOf(List<Entry> rows) {
        long t = 0;
        for (Entry e : rows) if (e.min() > 0) t += e.min();
        return t;
    }

    // ---- 7 pdf / 8 xls / 9 csv ----------------------------------------

    private void export(String kind) {
        out.title("EXPORT " + kind.toUpperCase());
        emptyCheck();
        Period p = in.period("period", svc.min(), svc.max());
        Report r = svc.report(p.from(), p.to());
        if (r.lines().isEmpty()) { out.bad("nothing between " + p.label()); return; }
        Path dir = Path.of(System.getProperty("user.dir"));
        String base = Ver.NAME + "-" + Str.iso(p.from()) + "_" + Str.iso(p.to()) + "." + kind;
        String typed = in.text("file", dir.resolve(base).toString());
        Path target = Path.of(typed);
        if (!target.isAbsolute()) target = dir.resolve(target);
        String name = target.getFileName().toString();
        if (!name.toLowerCase().endsWith("." + kind)) target = Path.of(target + "." + kind);

        if (target.toFile().exists() && !in.yes("overwrite " + target.getFileName() + "?", true)) {
            out.note("cancelled");
            return;
        }

        byte[] bytes = switch (kind) {
            case "csv" -> Csv.write(target, r);
            case "xls" -> Xls.write(target, r);
            default -> Pdf.write(target, r);
        };
        out.ok(target + "   " + human(bytes.length) + "   " + r.lines().size() + " lines   " + r.totalLabel());
        if (in.yes("open it?", true)) openWith(target);
    }

    private static String human(int n) {
        if (n < 1024) return n + " B";
        if (n < 1048576) return String.format(Locale.ROOT, "%.1f KB", n / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", n / 1048576.0);
    }

    private void openWith(Path p) {
        String tool = System.getProperty("os.name", "").startsWith("Mac") ? "open" : "xdg-open";
        try {
            ProcessBuilder pb = new ProcessBuilder(tool, p.toString());
            pb.redirectErrorStream(true);
            pb.inheritIO();
            pb.start();
        } catch (java.io.IOException e) { out.warn("could not open: " + e.getMessage()); }
    }

    // ---- c config --------------------------------------------------------

    private void config() {
        out.title("CONFIG");
        Map<String, Object> c = svc.config();
        out.note("db      " + c.get("db"));
        String who = (String) c.get("who");
        out.note("user    " + (who.isEmpty() ? "-" : who));
        out.note("records " + c.get("records") + "   " + range(c));
        out.note("csv     sep '" + Csv.SEP + "'  (PONTO_CSV_SEP)");
        out.note("java    " + c.get("java") + "   sqlite " + c.get("sqlite"));
        String off = (String) c.get("off");
        out.note("off     " + (off.isEmpty() ? "-" : off));
        for (Object s : (List<?>) c.get("nextOff")) out.note("day off " + s);
        String w = in.text("user", who.isEmpty() ? "-" : who);
        svc.setConfig(w, null, false);
        out.ok("user " + (w.equals("-") ? "-" : w));
        String o = in.text("off", off.isEmpty() ? "-" : off);
        svc.setConfig(null, o, false, s -> out.warn("off: '" + Str.cut(s, 24) + "'   ·   not a date, ignored"));
        out.ok("off " + (o.equals("-") ? "-" : Holidays.extra().size() + " day(s)"));
        if (in.yes("vacuum the db?", false)) { svc.setConfig(null, null, true); out.ok("vacuumed"); }
    }

    private String range(Map<String, Object> c) {
        String a = (String) c.get("from"), b = (String) c.get("to");
        return a.isEmpty() ? "-" : a + " .. " + b;
    }

    private void help() {
        out.title("KEYS");
        out.note("1 new   2 edit [id]   3 delete [id id.. | a | dd/mm]   4 list [n]");
        out.note("5 sum   6 find [text]   7 pdf   8 xls   9 csv   d by date");
        out.note("c config   x clear screen   ? help   0 quit");
        out.note("by date  2026 (year) · 10/2026 · oct · oct 2026 · 01/10/2026 · today");
        out.note("         4 2026 works the same way as d 2026");
        out.note("dates  08/10 · 08/10/2026 · 2026-10-08 · 5oct · today · y · -3");
        out.note("times  07:30 · 07h30 · 0730 · 8 · now          '-' clears a field");
        out.note("db     " + svc.dbPath() + "   (PONTO_DB / --db=path)");
    }
}
