package ponto.model;

import ponto.Bad;
import ponto.NeedConfirm;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final Companies companies;
    private final Object lock = new Object();
    private Files files;
    private Minio storage;

    public Service(Db db) {
        this.db = db;
        this.repo = new Repo(db);
        this.notes = new Notes(db, lock);
        this.companies = new Companies(db, lock);
    }

    /** Markdown notes: same SQLite file, unrelated to the timesheet. */
    public Notes notes() { return notes; }

    /** Workplaces: punches, notes, jornada and folgas of the active one. */
    public Companies companies() { return companies; }

    /** Files attached to notes: index in SQLite, bytes in S3 (MinIO). */
    public Files files() {
        synchronized (lock) {
            if (storage == null) storage = Minio.env();
            if (files == null) files = new Files(db, lock, storage);
            return files;
        }
    }

    /** Storage reachable? Shown in the config screen, not enforced at boot. */
    public boolean storageUp() {
        synchronized (lock) {
            if (storage == null) storage = Minio.env();
            return storage != null && storage.up();
        }
    }

    /** Weekend/user-off registration for the two modes' startup. */
    public void registerOff() { registerOff(s -> { }); }

    public void registerOff(Consumer<String> bad) {
        synchronized (lock) { applyOff(bad); }
    }

    private void applyOff() { applyOff(s -> { }); }

    private void applyOff(Consumer<String> bad) {
        Holidays.extra(offDates(companyLocked().off(), bad));
    }

    private Company companyLocked() { return companies.active(); }

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

    /** Active company's `off` as saved (the terminal edits + echoes it verbatim). */
    public String rawOff() { synchronized (lock) { return companyLocked().off(); } }

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
            applyOff();
            if (tin == null && tout == null && (just == null || just.isBlank()))
                throw new Bad("nada digitado");
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
            applyOff();
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
            applyOff();
            LocalDate from = LocalDate.now().withDayOfMonth(1);
            LocalDate to = LocalDate.now();
            long mins = 0;
            for (Entry e : repo.between(from, to)) if (e.min() > 0) mins += e.min();
            List<Entry> half = repo.openAll().stream().filter(Entry::open).toList();
            String since = "";
            if (!half.isEmpty() && half.getFirst().dia().isBefore(from))
                since = Str.d(half.getFirst().dia());
            LocalDate min = repo.min(), max = repo.max();
            Company c = companyLocked();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("who", who());
            m.put("company", c.name());
            m.put("companyId", c.id());
            m.put("records", repo.count());
            m.put("monthMins", mins);
            m.put("monthLabel", Dur.hm(mins));
            m.put("monthHours", Str.n(mins / 60.0));
            Bank b = bank();
            m.put("saldo", b.saldoLabel());
            m.put("saldoMins", b.saldoMins());
            m.put("planLabel", b.planLabel());
            m.put("bankNeg", b.neg());
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

    // ---- date-range filter (the web toolbar: from one month through another)
    // Same shapes as the pattern-filter methods above; the list/count cover the
    // whole span and the bank reuses saldoBetween, so the legal tolerance, the
    // falta/normal/off rules and the "never past today" cap all apply as-is.

    /** Paged rows of a date range, newest first (same shape as {@link #list}). */
    public List<Entry> listBetween(LocalDate from, LocalDate to, int n, int skip) {
        synchronized (lock) { return repo.between(from, to, n, skip); }
    }

    /** How many records inside the date range. */
    public int countBetween(LocalDate from, LocalDate to) {
        synchronized (lock) { return repo.countBetween(from, to); }
    }

    /** Closed-line minutes of the whole range, incomplete lines as 0. */
    public Totals totalsBetween(LocalDate from, LocalDate to) {
        synchronized (lock) {
            long mins = 0;
            int open = 0;
            for (Entry e : repo.between(from, to)) {
                if (e.min() > 0) mins += e.min();
                if (!e.closed()) open++;
            }
            return new Totals(mins, open);
        }
    }

    /** The tolerance-aware bank of the range, never past today. */
    public long overtimeBetween(LocalDate from, LocalDate to) {
        synchronized (lock) {
            applyOff();
            return saldoBetween(from, to);
        }
    }

    /** Default workday in minutes when the user has not set a daily hours. */
    public static final int DEFAULT_DAILY_MINS = 8 * 60;

    /**
     * Legal clock tolerance, CLT art. 58 §1º + TST Súmula 366: variations of
     * up to 5 minutes in a marking are neither counted as overtime nor
     * discounted; past the 5th minute the whole day balance counts, first
     * minutes included. The 10-minute daily cap is the sum of two 5-minute
     * markings — this app compares the whole day (batido - jornada), so the
     * trigger here is the 5th minute, symmetric in both directions.
     */
    public static final int TOLERANCE_MINS = 5;

    /** A day balance within the legal tolerance (±5 min) counts as 0. */
    private static long tolerated(long raw) {
        return Math.abs(raw) <= TOLERANCE_MINS ? 0 : raw;
    }

    /**
     * One day's bank contribution, the same for {@link #overtime} and
     * {@link #saldoBetween}: a falta keeps batido - jornada untouched (the
     * explicit flag, the jornada is owed); a day off banks everything worked
     * unless marked normal; where the rule is "batido - jornada" the legal
     * tolerance ({@link #TOLERANCE_MINS}) applies. A day without punches
     * contributes nothing.
     */
    private static long bankDay(long m, long daily, boolean flt, boolean normal, boolean off, boolean hasPunch) {
        if (flt) return m - daily;
        if (normal) return hasPunch ? tolerated(m - daily) : 0;
        if (off) return m;
        return hasPunch ? tolerated(m - daily) : 0;
    }

    /**
     * The configured workday in minutes (cfg `daily`, decimal hours like "8" or
     * "8,5"), or {@link #DEFAULT_DAILY_MINS} when unset or unreadable.
     */
    public int dailyMinutes() {
        synchronized (lock) {
            try { return parseDailyMins(companyLocked().daily()); }
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
     * Banco de horas over the filtered rows, one rule per day (the same as
     * {@link #monthSaldo}): a marked falta wins over everything (worked -
     * jornada, even on an empty day); a day off marked normal (the web toggle)
     * is ruled as a working day (worked - jornada, empty is ignored); a day
     * off credits everything worked (worked - 0); a working day with a closed
     * line counts worked - jornada minus the legal tolerance (±5 min counts
     * as zero, art. 58 §1º); an empty working day is ignored. A month
     * filter therefore walks the whole month, not only the days that have
     * lines. Never past today.
     */
    public long overtime(String q, String filter) {
        synchronized (lock) {
            applyOff();
            long daily = dailyMinutes();
            List<Entry> rows = rows(q, filter);
            Map<LocalDate, Long> mins = dayMins(rows);
            Set<LocalDate> fl = new HashSet<>(repo.flags("falta", "%"));
            Set<LocalDate> nm = new HashSet<>(repo.flags("normal", "%"));
            LocalDate today = LocalDate.now();
            Set<LocalDate> days = new HashSet<>();
            for (LocalDate d : mins.keySet()) if (!d.isAfter(today)) days.add(d);
            if (q == null || q.isBlank()) {
                String pattern = (filter == null || filter.isBlank()) ? "%" : Parse.like(filter).pattern();
                for (LocalDate d : fl) if (!d.isAfter(today) && like(pattern, d)) days.add(d);
            }
            long sum = 0;
            for (LocalDate d : days) {
                long m = mins.getOrDefault(d, 0L);
                sum += bankDay(m, daily, fl.contains(d), nm.contains(d),
                               !Holidays.off(d).isEmpty(), mins.containsKey(d));
            }
            return sum;
        }
    }

    /** Closed-line minutes per day of a row set (open lines and notes count as 0). */
    private static Map<LocalDate, Long> dayMins(List<Entry> rows) {
        Map<LocalDate, Long> m = new HashMap<>();
        for (Entry e : rows) if (e.closed() && e.min() > 0) m.merge(e.dia(), e.min(), Long::sum);
        return m;
    }

    /** A `dia LIKE` pattern from Parse.like against one ISO day, in Java. */
    private static boolean like(String pattern, LocalDate d) {
        String iso = Str.iso(d);
        String p = pattern.replace("%", "");
        boolean a = pattern.startsWith("%"), b = pattern.endsWith("%");
        if (a && b) return iso.contains(p);
        if (b) return iso.startsWith(p);
        if (a) return iso.endsWith(p);
        return iso.equals(p);
    }

    /**
     * The bank balance of a stretch of days, walking every day of it (a falta
     * counts even on a day without punches), never past today. Where the rule
     * is "batido - jornada" the legal tolerance applies
     * ({@link #TOLERANCE_MINS}).
     */
    private long saldoBetween(LocalDate from, LocalDate to) {
        LocalDate end = to.isAfter(LocalDate.now()) ? LocalDate.now() : to;
        if (from.isAfter(end)) return 0;
        long daily = dailyMinutes();
        Map<LocalDate, Long> mins = dayMins(repo.between(from, end));
        Set<LocalDate> fl = new HashSet<>(repo.flags("falta", "%"));
        Set<LocalDate> nm = new HashSet<>(repo.flags("normal", "%"));
        long sum = 0;
        for (LocalDate d = from; !d.isAfter(end); d = d.plusDays(1)) {
            long m = mins.getOrDefault(d, 0L);
            sum += bankDay(m, daily, fl.contains(d), nm.contains(d),
                           !Holidays.off(d).isEmpty(), mins.containsKey(d));
        }
        return sum;
    }

    /** The month's saldo: every day of the month up to today, the whole month when closed. */
    public long monthSaldo(YearMonth ym) {
        synchronized (lock) {
            applyOff();
            return saldoBetween(ym.atDay(1), ym.atEndOfMonth());
        }
    }

    /**
     * One month of the bank: the saldo, the workdays left (from today on,
     * without days off — unless one is marked normal — and without future
     * faltas) and the plan that zeroes the saldo by the end of the month.
     * `planMins` is the signed adjustment per remaining day: negative saldo
     * -> work more, positive -> work less.
     */
    public record Bank(YearMonth ym, long saldoMins, String saldoLabel, int dailyMins,
                       int remaining, int planMins, String planLabel, boolean neg) { }

    public Bank bank() { return bank(YearMonth.now()); }

    public Bank bank(YearMonth ym) {
        synchronized (lock) {
            applyOff();
            long saldo = saldoBetween(ym.atDay(1), ym.atEndOfMonth());
            LocalDate today = LocalDate.now();
            int remaining = 0;
            if (!ym.atEndOfMonth().isBefore(today)) {
                LocalDate from = ym.atDay(1).isAfter(today) ? ym.atDay(1) : today;
                Set<LocalDate> fl = new HashSet<>(repo.flags("falta", "%"));
                Set<LocalDate> nm = new HashSet<>(repo.flags("normal", "%"));
                for (LocalDate d = from; !d.isAfter(ym.atEndOfMonth()); d = d.plusDays(1)) {
                    if (fl.contains(d)) continue;
                    if (!Holidays.off(d).isEmpty() && !nm.contains(d)) continue;
                    remaining++;
                }
            }
            int planMins = 0;
            String planLabel = "";
            if (saldo != 0 && remaining > 0) {
                planMins = (int) Math.round(-saldo / (double) remaining);
                if (planMins == 0) planMins = saldo > 0 ? -1 : 1;   // the last day absorbs the rest
                long a = Math.abs(planMins);
                planLabel = a + (planMins < 0 ? " min a menos/dia" : " min a mais/dia");
            }
            return new Bank(ym, saldo, Dur.signed(saldo), dailyMinutes(),
                            remaining, planMins, planLabel, saldo < 0);
        }
    }

    /** One day of the month chart: worked minutes, the falta mark, the day kind. */
    public record BankDay(LocalDate dia, long mins, boolean falta, boolean normal, String kind) { }

    /**
     * Every day of the month, for the daily bar chart and the flag strip. The
     * days from today on stay in with their flags: a future day off flagged
     * normal re-enters the plan, a future falta leaves it. Zero minutes mean
     * no bars on the chart; the chips use the whole row set.
     */
    public List<BankDay> bankDays(YearMonth ym) {
        synchronized (lock) {
            applyOff();
            LocalDate today = LocalDate.now();
            LocalDate from = ym.atDay(1), to = ym.atEndOfMonth();
            if (from.isAfter(today)) return List.of();
            Map<LocalDate, Long> mins = dayMins(repo.between(from, to));
            Set<LocalDate> fl = new HashSet<>(repo.flags("falta", "%"));
            Set<LocalDate> nm = new HashSet<>(repo.flags("normal", "%"));
            List<BankDay> out = new ArrayList<>();
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                boolean falta = fl.contains(d);
                boolean normal = nm.contains(d);
                String off = Holidays.off(d);
                String kind = falta ? "falta"
                           : off.isEmpty() || normal ? "work"
                           : off.startsWith("weekend") ? "weekend"
                           : off.startsWith("holiday:") ? "holiday"
                           : off.startsWith("optional:") ? "optional" : "off";
                out.add(new BankDay(d, mins.getOrDefault(d, 0L), falta, normal, kind));
            }
            return out;
        }
    }

    /** One closed month of history (the 12-bar chart). */
    public record MonthSaldo(YearMonth ym, long saldoMins) { }

    /** The last `n` months ending at `ym`, oldest first. */
    public List<MonthSaldo> history(YearMonth ym, int n) {
        synchronized (lock) {
            applyOff();
            List<MonthSaldo> out = new ArrayList<>();
            for (int i = n - 1; i >= 0; i--) {
                YearMonth m = ym.minusMonths(i);
                out.add(new MonthSaldo(m, saldoBetween(m.atDay(1), m.atEndOfMonth())));
            }
            return out;
        }
    }

    // ---- falta: a flag of the whole day, never a punch line ----------------

    public boolean falta(LocalDate d) {
        synchronized (lock) { return repo.flagOn(d, "falta"); }
    }

    public void falta(LocalDate d, boolean on) {
        synchronized (lock) {
            if (on) repo.flagSet(d, "falta");
            else repo.flagClear(d, "falta");
        }
    }

    public List<LocalDate> faltas() {
        synchronized (lock) { return repo.flags("falta", "%"); }
    }

    // ---- normal: a day off ruled as a working day (the web toggle) ---------

    public boolean normal(LocalDate d) {
        synchronized (lock) { return repo.flagOn(d, "normal"); }
    }

    public void normal(LocalDate d, boolean on) {
        synchronized (lock) {
            if (on) repo.flagSet(d, "normal");
            else repo.flagClear(d, "normal");
        }
    }

    public List<LocalDate> normais() {
        synchronized (lock) { return repo.flags("normal", "%"); }
    }

    /**
     * What marking this day-off-as-normal does to the bank, as one warning
     * line: the batidas pay the jornada (they stop banking); a falta on the
     * same day still wins; a working day is already ruled like this. "" plain.
     */
    public String normalWarn(LocalDate d) {
        synchronized (lock) {
            applyOff();
            if (repo.flagOn(d, "falta")) return "o dia também tem falta — a falta ganha";
            if (Holidays.off(d).isEmpty()) return "o dia não é dia off (sem efeito)";
            return "batidas pagam a jornada (não somam banco)";
        }
    }

    /**
     * What marking this falta does to the bank, as one warning line (a warning,
     * never a wall): on a day off the falta deducts the jornada; on a working
     * day that already has closed lines it changes nothing. "" when plain.
     */
    public String faltaWarn(LocalDate d) {
        synchronized (lock) {
            applyOff();
            String off = Holidays.off(d);
            if (!off.isEmpty()) return "dia off (" + off + ")   ·   a falta deduz a jornada";
            for (Entry e : repo.on(d)) if (e.closed()) return "o dia já tem batidas (a falta não muda o saldo)";
            return "";
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
            applyOff();
            return Holidays.off(d);
        }
    }

    public Report report(LocalDate from, LocalDate to) {
        synchronized (lock) {
            applyOff();
            Period p = new Period(from, to);
            long saldo = saldoBetween(from, to);
            int faltas = 0;
            for (LocalDate d : repo.flags("falta", "%"))
                if (!d.isBefore(from) && !d.isAfter(to)) faltas++;
            LocalDate today = LocalDate.now();
            String plano = (!from.isAfter(today) && !to.isBefore(today)) ? bank().planLabel() : "";
            return new Report(new Report.Head(who(), companyLocked().name(), db.file.toString(), p, stamp()),
                              repo.between(from, to), saldo, faltas, plano);
        }
    }

    public Map<String, Object> config() {
        synchronized (lock) {
            applyOff();
            Company c = companyLocked();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("who", who());
            m.put("company", c.name());
            m.put("companyId", c.id());
            m.put("off", c.off());
            m.put("daily", c.daily());
            m.put("dailyMins", dailyMinutes());
            m.put("db", db.file.toString());
            m.put("records", repo.count());
            m.put("from", Str.d(repo.min()));
            m.put("to", Str.d(repo.max()));
            m.put("java", System.getProperty("java.version"));
            m.put("sqlite", db.version());
            m.put("storage", storageUp());
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
            Company cur = companyLocked();
            String nextOff = off == null ? null : (off.equals("-") ? "" : off);
            String nextDaily = daily;
            if (nextOff != null || nextDaily != null)
                companies.update(cur.id(), cur.name(), nextOff, nextDaily);
            if (nextOff != null) Holidays.extra(offDates(nextOff, bad));
            db.setCfg("asked", "1");
            if (vacuum) db.vacuum();
            return config();
        }
    }

    public Company activate(int id) {
        synchronized (lock) {
            companies.activate(id);
            applyOff();
            return companyLocked();
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
