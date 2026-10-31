package ponto.model;

import ponto.Bad;
import ponto.NeedConfirm;

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
    private final Notes notes;
    private final Object lock = new Object();

    public Service(Db db) {
        this.db = db;
        this.repo = new Repo(db);
        this.notes = new Notes(db, lock);
    }

    /** Markdown notes: same SQLite file, unrelated to the timesheet. */
    public Notes notes() { return notes; }

    /** Weekend/user-off registration for the two modes' startup. */
    public void registerOff() { registerOff(s -> { }); }

    public void registerOff(Consumer<String> bad) { Holidays.extra(offDates(db.cfg("off"), bad)); }

    public int count() { synchronized (lock) { return repo.count(); } }

    public LocalDate min() { synchronized (lock) { return repo.min(); } }

    public LocalDate max() { synchronized (lock) { return repo.max(); } }

    public int countOn(LocalDate d) { synchronized (lock) { return repo.countOn(d); } }

    public List<Entry> recent(int n, int skip) { synchronized (lock) { return repo.recent(n, skip); } }

    public List<Entry> search(String q) { synchronized (lock) { return repo.search(q); } }

    public List<Entry> byDay(String pattern, int n, int skip) {
        synchronized (lock) { return repo.byDay(pattern, n, skip); }
    }

    public int countDay(String pattern) { synchronized (lock) { return repo.countDay(pattern); } }

    public String dbPath() { synchronized (lock) { return db.file.toString(); } }

    /** cfg `off` as saved (the terminal edits + echoes it verbatim). */
    public String rawOff() { synchronized (lock) { return db.cfg("off"); } }

    /** cfg `who` as saved (without the $PONTO_USER fallback of who()). */
    public String rawWho() { synchronized (lock) { return db.cfg("who", ""); } }

    public boolean asked() { synchronized (lock) { return db.cfg("asked").equals("1"); } }

    public void markAsked() { synchronized (lock) { db.setCfg("asked", "1"); } }

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

    /**
     * Closed-line minutes of every record matching the list filter
     * (the whole set, not the current page). Incomplete lines count as 0.
     */
    public Totals listTotals(String q, String filter) {
        synchronized (lock) {
            long mins = 0;
            int open = 0;
            for (Entry e : rows(q, filter)) {
                if (e.min() > 0) mins += e.min();
                if (!e.closed()) open++;
            }
            return new Totals(mins, open);
        }
    }

    public record Totals(long mins, int open) {
        public String dur() { return Dur.hm(mins); }
        public String hours() { return Dur.dec(mins); }
    }

    /** Default workday in minutes when the user has not set a daily hours. */
    public static final int DEFAULT_DAILY_MINS = 8 * 60;

    /**
     * The configured workday in minutes (cfg `daily`, decimal hours like "8" or
     * "8,5"), or {@link #DEFAULT_DAILY_MINS} when unset or unreadable.
     */
    public int dailyMinutes() {
        synchronized (lock) {
            String raw = db.cfg("daily");
            try { return parseDailyMins(raw); }
            catch (Bad e) { return DEFAULT_DAILY_MINS; }
        }
    }

    /**
     * Parse the cfg `daily` value into minutes: blank or "-" means unset
     * (default 8h); otherwise decimal hours ("8", "8.5", "8,5") or h:mm ("8:30"),
     * between 0.5h and 24h. The empty string is returned for "unset".
     */
    public static int parseDailyMins(String raw) {
        if (raw == null || raw.isBlank() || raw.equals("-")) return DEFAULT_DAILY_MINS;
        String s = raw.trim().replace(',', '.');
        int mins;
        if (s.contains(":")) {
            String[] p = s.split(":");
            if (p.length != 2) throw new Bad("daily: " + raw);
            int h = Integer.parseInt(p[0].trim());
            int m = Integer.parseInt(p[1].trim());
            if (m >= 60) throw new Bad("daily: " + raw);
            mins = h * 60 + m;
        } else {
            double v;
            try { v = Double.parseDouble(s); }
            catch (NumberFormatException e) { throw new Bad("daily: " + raw); }
            if (v < 0) throw new Bad("daily: " + raw);
            mins = (int) Math.round(v * 60.0);
        }
        if (mins < 30 || mins > 24 * 60) throw new Bad("daily: " + raw);
        return mins;
    }

    /**
     * Banco de horas over the filtered rows: for each day that has at least one
     * closed line, add (day minutes - workday). Days over the workday count as
     * overtime, days under as negative. Unfiltered (both null) covers everything
     * recorded. Reuses the same row set as listTotals.
     */
    public long overtime(String q, String filter) {
        synchronized (lock) {
            long daily = dailyMinutes();
            long sum = 0;
            Long cur = null;
            long curMins = 0;
            int curClosed = 0;
            for (Entry e : rows(q, filter)) {
                long key = e.dia().toEpochDay();
                if (cur != null && key != cur) {
                    if (curClosed > 0) sum += curMins - daily;
                    cur = key; curMins = 0; curClosed = 0;
                } else if (cur == null) {
                    cur = key;
                }
                if (e.closed() && e.min() > 0) { curMins += e.min(); curClosed++; }
            }
            if (cur != null && curClosed > 0) sum += curMins - daily;
            return sum;
        }
    }

    private List<Entry> rows(String q, String filter) {
        if (q != null && !q.isBlank()) return repo.search(q);
        if (filter != null && !filter.isBlank()) return repo.byDay(Parse.like(filter).pattern());
        return repo.all();
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

    public Map<String, Object> config() {
        synchronized (lock) {
            Holidays.extra(offDates(db.cfg("off")));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("who", who());
            m.put("off", db.cfg("off"));
            m.put("daily", db.cfg("daily"));
            m.put("dailyMins", dailyMinutes());
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
        return setConfig(who, off, null, vacuum, s -> { });
    }

    /** Same, but the terminal keeps its "off: '...' not a date" line for bad dates. */
    public Map<String, Object> setConfig(String who, String off, boolean vacuum, Consumer<String> bad) {
        return setConfig(who, off, null, vacuum, bad);
    }

    /**
     * Save settings. `daily` is the workday in decimal hours ("8", "8.5") or
     * h:mm; null leaves it untouched, ""/"-" clears it (back to 8h). Invalid
     * values throw Bad.
     */
    public Map<String, Object> setConfig(String who, String off, String daily, boolean vacuum, Consumer<String> bad) {
        synchronized (lock) {
            if (who != null) db.setCfg("who", who.equals("-") ? "" : who);
            if (off != null) {
                db.setCfg("off", off.equals("-") ? "" : off);
                Holidays.extra(offDates(off, bad));
            }
            if (daily != null) {
                String d = daily.trim();
                if (d.isEmpty() || d.equals("-")) db.setCfg("daily", "");
                else db.setCfg("daily", dailyLabel(parseDailyMins(d)));
            }
            db.setCfg("asked", "1");
            if (vacuum) db.vacuum();
            return config();
        }
    }

    /** 480 -> "8", 510 -> "8.5": the stored cfg value of the workday. */
    public static String dailyLabel(int mins) {
        String v = String.format(java.util.Locale.ROOT, "%.2f", mins / 60.0);
        if (v.endsWith("00")) v = v.substring(0, v.length() - 3);
        else if (v.endsWith("0")) v = v.substring(0, v.length() - 1);
        return v;
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

    private static String stamp() {
        LocalDateTime n = LocalDateTime.now();
        return Str.d(n.toLocalDate()) + " " + Str.t(n.toLocalTime());
    }
}
