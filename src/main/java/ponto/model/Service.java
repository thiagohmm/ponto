package ponto.model;

import ponto.Bad;
import ponto.NeedConfirm;
import ponto.Problema;
import ponto.view.Cols;
import ponto.view.Csv;
import ponto.view.Pdf;
import ponto.view.Xls;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Shared timesheet rules for the terminal menu and the web API.
 * One lock around SQLite: the JDBC connection is not thread-safe.
 */
public final class Service {

    public record Warn(boolean sameTime, boolean midnight, String midnightLabel, Entry overlap) {
        static Warn empty() { return new Warn(false, false, "", null); }
        static Warn same() { return new Warn(true, false, "", null); }
    }

    private final Db db;
    private final Repo repo;
    private final Object lock = new Object();

    public Service(Db db) { this.db = db; this.repo = new Repo(db); }

    Repo repo() { return repo; }
    public Db db() { return db; }

    public String who() {
        synchronized (lock) {
            String saved = db.cfg("who", "");
            if (!saved.isEmpty()) return saved;
            String env = System.getenv("PONTO_USER");
            return env == null ? "" : env;
        }
    }

    public Warn inspect(Entry e, int skip) {
        synchronized (lock) { return inspectLocked(e, skip); }
    }

    private Warn inspectLocked(Entry e, int skip) {
        if (!e.closed()) return Warn.empty();
        int a = Str.tod(e.tin()), b = Str.tod(e.tout());
        if (a == b) return Warn.same();
        boolean midnight = b < a;
        String midnightLabel = midnight ? Dur.hm(b - a + 1440) : "";
        int bb = midnight ? b + 1440 : b;
        for (Entry o : repo.on(e.dia())) {
            if (o.id() == skip || !o.closed()) continue;
            int x = Str.tod(o.tin()), y = Str.tod(o.tout());
            if (y <= x) y += 1440;
            if (bb <= x || a >= y) continue;
            return new Warn(false, midnight, midnightLabel, o);
        }
        return new Warn(false, midnight, midnightLabel, null);
    }

    void assertOk(Entry e, int skip, boolean confirmMidnight, boolean confirmOverlap) {
        Warn w = inspectLocked(e, skip);
        if (w.sameTime()) throw new Bad("in = out");
        if (w.midnight() && !confirmMidnight)
            throw new NeedConfirm("midnight", "cruza meia-noite (" + w.midnightLabel() + ")");
        if (w.overlap() != null && !confirmOverlap) {
            Entry o = w.overlap();
            throw new NeedConfirm("overlap",
                    "sobreposto a #" + o.id() + " " + o.stamp() + " (" + o.dur() + ")");
        }
    }

    public Entry create(LocalDate dia, LocalTime tin, LocalTime tout, String just,
                 boolean closeOpen, boolean confirmMidnight, boolean confirmOverlap) {
        synchronized (lock) {
            Holidays.extra(offDates(db.cfg("off")));
            if (tin == null && tout == null && (just == null || just.isBlank()))
                throw new Bad("nothing typed");
            List<Entry> same = repo.on(dia);
            List<Entry> open = same.stream().filter(e -> e.tin() != null && e.tout() == null).toList();
            if (closeOpen && open.size() == 1 && tout != null) {
                Entry o = open.getFirst();
                Entry done = new Entry(o.id(), o.dia(), o.tin(), tout, o.just(), o.crea());
                assertOk(done, done.id(), confirmMidnight, confirmOverlap);
                repo.update(done);
                return repo.byId(done.id());
            }
            String j = just == null ? "" : just;
            Entry fresh = new Entry(0, dia, tin, tout, j, "");
            assertOk(fresh, 0, confirmMidnight, confirmOverlap);
            int id = repo.insert(dia, fresh.tin(), fresh.tout(), j);
            return repo.byId(id);
        }
    }

    public Entry update(int id, LocalDate dia, LocalTime tin, LocalTime tout, String just,
                 boolean confirmMidnight, boolean confirmOverlap) {
        synchronized (lock) {
            Holidays.extra(offDates(db.cfg("off")));
            Entry e = repo.byId(id);
            String j = just == null ? "" : just;
            Entry now = new Entry(e.id(), dia, tin, tout, j, e.crea());
            if (now.open() && now.just().isEmpty()) throw new Bad("a half line needs a justification");
            if (now.closed() && now.min() == 0) throw new Bad("in = out");
            assertOk(now, now.id(), confirmMidnight, confirmOverlap);
            if (tin == null && tout == null) repo.clearTimes(now);
            else repo.update(now);
            return repo.byId(id);
        }
    }

    public void delete(int id) {
        synchronized (lock) { repo.delete(id); }
    }

    public int deleteDay(LocalDate d) {
        synchronized (lock) { return repo.deleteDay(d); }
    }

    public Map<String, Object> home() {
        synchronized (lock) {
            Holidays.extra(offDates(db.cfg("off")));
            LocalDate from = LocalDate.now().withDayOfMonth(1);
            LocalDate to = LocalDate.now();
            long mins = 0;
            for (Entry e : repo.between(from, to)) if (e.min() > 0) mins += e.min();
            List<Entry> half = repo.openAll().stream().filter(Entry::open).toList();
            String since = "";
            if (!half.isEmpty() && half.getFirst().dia().isBefore(from))
                since = Str.d(half.getFirst().dia());
            LocalDate min = repo.min(), max = repo.max();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("who", who());
            m.put("records", repo.count());
            m.put("monthMins", mins);
            m.put("monthLabel", Dur.hm(mins));
            m.put("monthHours", Str.n(mins / 60.0));
            m.put("open", half.size());
            m.put("openSince", since);
            m.put("db", db.file.toString());
            m.put("min", min == null ? "" : Str.iso(min));
            m.put("max", max == null ? "" : Str.iso(max));
            m.put("from", Str.d(min));
            m.put("to", Str.d(max));
            return m;
        }
    }

    public List<Entry> list(String q, String filter, int n, int skip) {
        synchronized (lock) {
            if (q != null && !q.isBlank()) return repo.search(q);
            if (filter != null && !filter.isBlank()) {
                Parse.Like f = Parse.like(filter);
                return repo.byDay(f.pattern(), n, skip);
            }
            return repo.recent(n, skip);
        }
    }

    public int count(String q, String filter) {
        synchronized (lock) {
            if (q != null && !q.isBlank()) return repo.search(q).size();
            if (filter != null && !filter.isBlank()) return repo.countDay(Parse.like(filter).pattern());
            return repo.count();
        }
    }

    public Entry byId(int id) {
        synchronized (lock) { return repo.byId(id); }
    }

    public List<Entry> on(LocalDate d) {
        synchronized (lock) { return repo.on(d); }
    }

    public String holiday(LocalDate d) {
        synchronized (lock) {
            Holidays.extra(offDates(db.cfg("off")));
            return Holidays.off(d);
        }
    }

    public Report report(LocalDate from, LocalDate to) {
        synchronized (lock) {
            Period p = new Period(from, to);
            return new Report(new Report.Head(who(), db.file.toString(), p, stamp()), repo.between(from, to));
        }
    }

    public byte[] export(String kind, LocalDate from, LocalDate to) {
        Report r = report(from, to);
        if (r.lines().isEmpty()) throw new Problema("nothing between " + new Period(from, to).label());
        try {
            Path tmp = Files.createTempFile("ponto-", "." + kind);
            try {
                return switch (kind) {
                    case "csv" -> Csv.write(tmp, r);
                    case "xls" -> Xls.write(tmp, r);
                    case "pdf" -> Pdf.write(tmp, r);
                    default -> throw new Bad("export: " + kind);
                };
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (java.io.IOException e) {
            throw new Problema("export: " + e.getMessage());
        }
    }

    public Map<String, Object> config() {
        synchronized (lock) {
            Holidays.extra(offDates(db.cfg("off")));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("who", who());
            m.put("off", db.cfg("off"));
            m.put("db", db.file.toString());
            m.put("records", repo.count());
            m.put("from", Str.d(repo.min()));
            m.put("to", Str.d(repo.max()));
            m.put("java", System.getProperty("java.version"));
            m.put("sqlite", db.version());
            m.put("nextOff", Holidays.next(4));
            return m;
        }
    }

    public Map<String, Object> setConfig(String who, String off, boolean vacuum) {
        synchronized (lock) {
            if (who != null) db.setCfg("who", who.equals("-") ? "" : who);
            if (off != null) {
                db.setCfg("off", off.equals("-") ? "" : off);
                Holidays.extra(offDates(off));
            }
            db.setCfg("asked", "1");
            if (vacuum) db.vacuum();
            return config();
        }
    }

    public static List<LocalDate> offDates(String raw) { return offDates(raw, s -> {}); }

    public static List<LocalDate> offDates(String raw, Consumer<String> bad) {
        List<LocalDate> r = new ArrayList<>();
        if (raw == null || raw.isBlank() || raw.equals("-")) return r;
        for (String s : raw.split("[;, ]+")) {
            if (s.isBlank()) continue;
            try { r.add(Parse.date(s)); }
            catch (Bad ignored) { bad.accept(s); }
        }
        return r;
    }

    public static LocalDate parseDate(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        return Parse.date(raw);
    }

    public static LocalTime parseTime(String raw) {
        if (raw == null || raw.isBlank() || raw.equals("-")) return null;
        return Parse.time(raw);
    }

    public static Map<String, Object> json(Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("dia", Str.d(e.dia()));
        m.put("diaIso", Str.iso(e.dia()));
        m.put("d3", Str.d3(e.dia()));
        m.put("tin", Str.t(e.tin()));
        m.put("tout", Str.t(e.tout()));
        m.put("just", e.just());
        m.put("crea", e.crea());
        m.put("dur", Cols.dur(e));
        m.put("hours", Cols.hours(e));
        m.put("open", e.open());
        m.put("closed", e.closed());
        m.put("note", e.empty());
        return m;
    }

    public static Map<String, Object> jsonDay(Report.Day d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dia", Str.d(d.dia()));
        m.put("diaIso", Str.iso(d.dia()));
        m.put("d3", d.d3());
        m.put("lines", d.lines());
        m.put("mins", d.mins());
        m.put("dur", Dur.hm(d.mins()));
        m.put("hours", d.dec());
        return m;
    }

    private static String stamp() {
        LocalDateTime n = LocalDateTime.now();
        return Str.d(n.toLocalDate()) + " " + Str.t(n.toLocalTime());
    }
}
