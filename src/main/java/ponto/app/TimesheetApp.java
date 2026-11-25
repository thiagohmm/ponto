package ponto.app;

import ponto.infra.Db;
import ponto.infra.Repo;
import ponto.infra.Reports;
import ponto.model.BankRules;
import ponto.model.Company;
import ponto.model.Dur;
import ponto.model.Entry;
import ponto.model.Holidays;
import ponto.model.Parse;
import ponto.model.Report;
import ponto.model.Str;
import ponto.model.Time;
import ponto.Bad;
import ponto.NeedConfirm;

import java.time.LocalDate;
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
import java.util.function.Supplier;

/**
 * Timesheet rules: punches, bank balance, falta/normal flags, home/config shapes.
 * Holds the shared JDBC lock with the rest of the application services.
 */
public final class TimesheetApp {

    public record BankSnapshot(Service.Bank bank, List<Service.BankDay> days, List<Service.MonthSaldo> months) { }

    public record EntriesPage(List<Entry> entries, Service.Totals totals, long total, long overtime,
                              List<LocalDate> faltas, List<LocalDate> normais) { }

    private final Db db;
    private final Repo repo;
    private final Object lock;
    private final Holidays calendar;
    private final Reports reports;
    private final Supplier<Company> company;
    private final Supplier<String> who;

    TimesheetApp(Db db, Object lock, Holidays calendar, Supplier<Company> company, Supplier<String> who) {
        this.db = db;
        this.lock = lock;
        this.calendar = calendar;
        this.repo = new Repo(db);
        this.company = company;
        this.who = who;
        this.reports = new Reports(db, repo, who, () -> company.get().name());
    }

    Repo repo() { return repo; }
    Holidays calendar() { return calendar; }
    Object lock() { return lock; }
    Db db() { return db; }

    public void registerOff() { registerOff(s -> { }); }
    public void registerOff(Consumer<String> bad) {
        synchronized (lock) { Service.offDates(company.get().off(), bad); }
    }

    public Repo.Stats stats() { synchronized (lock) { return repo.stats(); } }
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
    public String dbPath() { synchronized (lock) { return Service.maskDbUrl(db.url); } }
    public String rawOff() { synchronized (lock) { return company.get().off(); } }

    public Service.Warn inspect(Entry e, int skip) {
        synchronized (lock) { return inspectLocked(e, skip); }
    }

    private Service.Warn inspectLocked(Entry e, int skip) {
        if (!e.closed()) return Service.Warn.empty();
        int a = Str.tod(e.tin()), b = Str.tod(e.tout());
        if (a == b) return Service.Warn.same();
        boolean midnight = b < a;
        String midnightLabel = midnight ? Dur.hm(b - a + 1440) : "";
        int bb = midnight ? b + 1440 : b;
        for (Entry o : repo.on(e.dia())) {
            if (o.id() == skip || !o.closed()) continue;
            int x = Str.tod(o.tin()), y = Str.tod(o.tout());
            if (y <= x) y += 1440;
            if (bb <= x || a >= y) continue;
            return new Service.Warn(false, midnight, midnightLabel, o);
        }
        return new Service.Warn(false, midnight, midnightLabel, null);
    }

    void assertOk(Entry e, int skip, boolean confirmMidnight, boolean confirmOverlap) {
        Service.Warn w = inspectLocked(e, skip);
        if (w.sameTime()) throw new Bad("entrada = saída");
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
            Entry e = repo.byId(id);
            String j = just == null ? "" : just;
            Entry now = new Entry(e.id(), dia, tin, tout, j, e.crea());
            if (now.open() && now.just().isEmpty()) throw new Bad("linha incompleta precisa de justificativa");
            if (now.closed() && now.min() == 0) throw new Bad("entrada = saída");
            assertOk(now, now.id(), confirmMidnight, confirmOverlap);
            if (tin == null && tout == null) repo.clearTimes(now);
            else repo.update(now);
            return repo.byId(id);
        }
    }

    public void delete(int id) { synchronized (lock) { repo.delete(id); } }
    public int deleteDay(LocalDate d) { synchronized (lock) { return repo.deleteDay(d); } }

    public Entry byId(int id) { synchronized (lock) { return repo.byId(id); } }
    public List<Entry> on(LocalDate d) { synchronized (lock) { return repo.on(d); } }

    public HomeView home() {
        synchronized (lock) {
            LocalDate today = Time.today();
            LocalDate from = today.withDayOfMonth(1);
            List<Entry> monthRows = repo.between(from, today);
            long mins = 0;
            for (Entry e : monthRows) if (e.min() > 0) mins += e.min();
            List<Entry> half = repo.openAll().stream().filter(Entry::open).toList();
            String since = "";
            if (!half.isEmpty() && half.getFirst().dia().isBefore(from))
                since = Str.d(half.getFirst().dia());
            Repo.Stats st = repo.stats();
            Company c = company.get();
            int daily = dailyMinutes(c);
            Repo.DayFlags flags = repo.allFlags();
            Set<LocalDate> fl = new HashSet<>(flags.faltas());
            Set<LocalDate> nm = new HashSet<>(flags.normais());
            Set<LocalDate> extraOff = calendar.extraDays();
            Service.Bank b = computeBank(YearMonth.from(today), today, daily, dayMins(monthRows), fl, nm, extraOff);
            return new HomeView(
                    who.get(), c.name(), c.id(), st.count(),
                    mins, Dur.hm(mins), Str.n(mins / 60.0),
                    b.saldoLabel(), b.saldoMins(), b.planLabel(), b.neg(),
                    half.size(), since, Service.maskDbUrl(db.url),
                    st.min() == null ? "" : Str.iso(st.min()),
                    st.max() == null ? "" : Str.iso(st.max()),
                    Str.d(st.min()), Str.d(st.max()));
        }
    }

    public List<Entry> list(String q, String filter, int n, int skip) {
        synchronized (lock) {
            if (q != null && !q.isBlank()) return rows(q, filter);
            if (filter != null && !filter.isBlank()) {
                Parse.Like f = Parse.like(filter);
                return repo.byDay(f.pattern(), n, skip);
            }
            return repo.recent(n, skip);
        }
    }

    public int count(String q, String filter) {
        synchronized (lock) {
            if (q != null && !q.isBlank()) return rows(q, filter).size();
            if (filter != null && !filter.isBlank()) return repo.countDay(Parse.like(filter).pattern());
            return repo.count();
        }
    }

    public Service.Totals listTotals(String q, String filter) {
        synchronized (lock) {
            return computeTotals(rows(q, filter));
        }
    }

    public List<Entry> listBetween(LocalDate from, LocalDate to, int n, int skip) {
        synchronized (lock) { return repo.between(from, to, n, skip); }
    }

    public Service.FilteredRange filteredRange(LocalDate from, LocalDate to, String query, int n, int skip) {
        synchronized (lock) {
            var matches = repo.search(query).stream()
                .filter(e -> !e.dia().isBefore(from) && !e.dia().isAfter(to)).toList();
            Service.Totals tot = computeTotals(matches);
            Repo.DayFlags flags = repo.allFlags();
            Set<LocalDate> fl = new HashSet<>(flags.faltas());
            Set<LocalDate> nm = new HashSet<>(flags.normais());
            Set<LocalDate> extraOff = calendar.extraDays();
            long daily = dailyMinutes();
            LocalDate today = Time.today();
            long overtime = 0;
            for (var day : dayMins(matches).entrySet()) {
                if (!day.getKey().isAfter(today))
                    overtime += BankRules.day(day.getValue(), daily, fl.contains(day.getKey()),
                        nm.contains(day.getKey()), !calendar.off(day.getKey(), extraOff).isEmpty(), true);
            }
            return new Service.FilteredRange(slice(matches, n, skip), tot, matches.size(), overtime);
        }
    }

    /** Single-pass range query for GET /api/entries?from=...&to=... (2 SQL queries total). */
    public EntriesPage entriesRange(LocalDate from, LocalDate to, String query, int n, int skip) {
        synchronized (lock) {
            Repo.DayFlags flags = repo.allFlags();
            Set<LocalDate> fl = new HashSet<>(flags.faltas());
            Set<LocalDate> nm = new HashSet<>(flags.normais());
            Set<LocalDate> extraOff = calendar.extraDays();
            long daily = dailyMinutes();
            LocalDate today = Time.today();
            if (query != null && !query.isBlank()) {
                var matches = repo.search(query).stream()
                        .filter(e -> !e.dia().isBefore(from) && !e.dia().isAfter(to)).toList();
                Service.Totals tot = computeTotals(matches);
                long overtime = 0;
                for (var day : dayMins(matches).entrySet()) {
                    if (!day.getKey().isAfter(today))
                        overtime += BankRules.day(day.getValue(), daily, fl.contains(day.getKey()),
                                nm.contains(day.getKey()), !calendar.off(day.getKey(), extraOff).isEmpty(), true);
                }
                return new EntriesPage(slice(matches, n, skip), tot, matches.size(), overtime, flags.faltas(), flags.normais());
            }
            List<Entry> all = repo.between(from, to);
            Service.Totals tot = computeTotals(all);
            long overtime = computeSaldoBetween(from, to, today, daily, dayMins(all), fl, nm, extraOff);
            return new EntriesPage(slice(all, n, skip), tot, all.size(), overtime, flags.faltas(), flags.normais());
        }
    }

    /** Single-pass filtered/unfiltered list for GET /api/entries (2 SQL queries total). */
    public EntriesPage entriesList(String q, String filter, int n, int skip) {
        synchronized (lock) {
            Repo.DayFlags flags = repo.allFlags();
            Set<LocalDate> fl = new HashSet<>(flags.faltas());
            Set<LocalDate> nm = new HashSet<>(flags.normais());
            Set<LocalDate> extraOff = calendar.extraDays();
            long daily = dailyMinutes();
            LocalDate today = Time.today();
            List<Entry> all = rows(q, filter);
            Service.Totals tot = computeTotals(all);
            long ot = computeOvertime(q, filter, all, today, daily, fl, nm, extraOff);
            List<Entry> page = (q != null && !q.isBlank()) ? all : slice(all, n, skip);
            return new EntriesPage(page, tot, all.size(), ot, flags.faltas(), flags.normais());
        }
    }

    public int countBetween(LocalDate from, LocalDate to) {
        synchronized (lock) { return repo.countBetween(from, to); }
    }

    public Service.Totals totalsBetween(LocalDate from, LocalDate to) {
        synchronized (lock) { return computeTotals(repo.between(from, to)); }
    }

    public long overtimeBetween(LocalDate from, LocalDate to) {
        synchronized (lock) { return saldoBetween(from, to); }
    }

    public int dailyMinutes() {
        synchronized (lock) { return dailyMinutes(company.get()); }
    }

    private static int dailyMinutes(Company c) {
        try { return Service.parseDailyMins(c.daily()); }
        catch (Bad e) { return Service.DEFAULT_DAILY_MINS; }
    }

    public long overtime(String q, String filter) {
        synchronized (lock) {
            Repo.DayFlags flags = repo.allFlags();
            return computeOvertime(q, filter, rows(q, filter), Time.today(), dailyMinutes(),
                    new HashSet<>(flags.faltas()), new HashSet<>(flags.normais()), calendar.extraDays());
        }
    }

    private long computeOvertime(String q, String filter, List<Entry> rows, LocalDate today, long daily,
                                 Set<LocalDate> fl, Set<LocalDate> nm, Set<LocalDate> extraOff) {
        Map<LocalDate, Long> mins = dayMins(rows);
        Set<LocalDate> days = new HashSet<>();
        for (LocalDate d : mins.keySet()) if (!d.isAfter(today)) days.add(d);
        if (q == null || q.isBlank()) {
            String pattern = (filter == null || filter.isBlank()) ? "%" : Parse.like(filter).pattern();
            for (LocalDate d : fl) if (!d.isAfter(today) && like(pattern, d)) days.add(d);
        }
        long sum = 0;
        for (LocalDate d : days) {
            long m = mins.getOrDefault(d, 0L);
            sum += BankRules.day(m, daily, fl.contains(d), nm.contains(d),
                    !calendar.off(d, extraOff).isEmpty(), mins.containsKey(d));
        }
        return sum;
    }

    private static Service.Totals computeTotals(List<Entry> rows) {
        long mins = 0;
        int open = 0;
        for (Entry e : rows) {
            if (e.min() > 0) mins += e.min();
            if (!e.closed()) open++;
        }
        return new Service.Totals(mins, open);
    }

    private static List<Entry> slice(List<Entry> rows, int n, int skip) {
        int from = Math.min(rows.size(), Math.max(0, skip));
        int to = Math.min(rows.size(), from + Math.max(0, n));
        return rows.subList(from, to);
    }

    private static Map<LocalDate, Long> dayMins(List<Entry> rows) {
        Map<LocalDate, Long> m = new HashMap<>();
        for (Entry e : rows) if (e.closed() && e.min() > 0) m.merge(e.dia(), e.min(), Long::sum);
        return m;
    }

    private static boolean like(String pattern, LocalDate d) {
        String iso = Str.iso(d);
        String p = pattern.replace("%", "");
        boolean a = pattern.startsWith("%"), b = pattern.endsWith("%");
        if (a && b) return iso.contains(p);
        if (b) return iso.startsWith(p);
        if (a) return iso.endsWith(p);
        return iso.equals(p);
    }

    private long computeSaldoBetween(LocalDate from, LocalDate to, LocalDate today, long daily,
                                     Map<LocalDate, Long> mins, Set<LocalDate> fl, Set<LocalDate> nm,
                                     Set<LocalDate> extraOff) {
        LocalDate end = to.isAfter(today) ? today : to;
        if (from.isAfter(end)) return 0;
        long sum = 0;
        for (LocalDate d = from; !d.isAfter(end); d = d.plusDays(1)) {
            long m = mins.getOrDefault(d, 0L);
            sum += BankRules.day(m, daily, fl.contains(d), nm.contains(d),
                    !calendar.off(d, extraOff).isEmpty(), mins.containsKey(d));
        }
        return sum;
    }

    private long saldoBetween(LocalDate from, LocalDate to) {
        LocalDate today = Time.today();
        LocalDate end = to.isAfter(today) ? today : to;
        if (from.isAfter(end)) return 0;
        Repo.DayFlags flags = repo.allFlags();
        return computeSaldoBetween(from, to, today, dailyMinutes(), dayMins(repo.between(from, end)),
                new HashSet<>(flags.faltas()), new HashSet<>(flags.normais()), calendar.extraDays());
    }

    public long monthSaldo(YearMonth ym) {
        synchronized (lock) { return saldoBetween(ym.atDay(1), ym.atEndOfMonth()); }
    }

    public Service.Bank bank() { return bank(Time.month()); }

    public Service.Bank bank(YearMonth ym) {
        synchronized (lock) {
            LocalDate today = Time.today();
            LocalDate from = ym.atDay(1), end = ym.atEndOfMonth().isAfter(today) ? today : ym.atEndOfMonth();
            Map<LocalDate, Long> mins = from.isAfter(end) ? Map.of() : dayMins(repo.between(from, end));
            Repo.DayFlags flags = repo.allFlags();
            return computeBank(ym, today, dailyMinutes(), mins,
                    new HashSet<>(flags.faltas()), new HashSet<>(flags.normais()), calendar.extraDays());
        }
    }

    private Service.Bank computeBank(YearMonth ym, LocalDate today, int daily, Map<LocalDate, Long> mins,
                                     Set<LocalDate> fl, Set<LocalDate> nm, Set<LocalDate> extraOff) {
        long saldo = computeSaldoBetween(ym.atDay(1), ym.atEndOfMonth(), today, daily, mins, fl, nm, extraOff);
        int remaining = 0;
        if (!ym.atEndOfMonth().isBefore(today)) {
            LocalDate from = ym.atDay(1).isAfter(today) ? ym.atDay(1) : today;
            for (LocalDate d = from; !d.isAfter(ym.atEndOfMonth()); d = d.plusDays(1)) {
                if (fl.contains(d)) continue;
                if (!calendar.off(d, extraOff).isEmpty() && !nm.contains(d)) continue;
                remaining++;
            }
        }
        int planMins = 0;
        String planLabel = "";
        if (saldo != 0 && remaining > 0) {
            planMins = (int) Math.round(-saldo / (double) remaining);
            if (planMins == 0) planMins = saldo > 0 ? -1 : 1;
            long a = Math.abs(planMins);
            planLabel = a + (planMins < 0 ? " min a menos/dia" : " min a mais/dia");
        }
        return new Service.Bank(ym, saldo, Dur.signed(saldo), daily, remaining, planMins, planLabel, saldo < 0);
    }

    public List<Service.BankDay> bankDays(YearMonth ym) {
        synchronized (lock) {
            LocalDate today = Time.today();
            LocalDate from = ym.atDay(1), to = ym.atEndOfMonth();
            if (from.isAfter(today)) return List.of();
            Map<LocalDate, Long> mins = dayMins(repo.between(from, to));
            Repo.DayFlags flags = repo.allFlags();
            return computeBankDays(ym, today, mins,
                    new HashSet<>(flags.faltas()), new HashSet<>(flags.normais()), calendar.extraDays());
        }
    }

    private List<Service.BankDay> computeBankDays(YearMonth ym, LocalDate today, Map<LocalDate, Long> mins,
                                                  Set<LocalDate> fl, Set<LocalDate> nm, Set<LocalDate> extraOff) {
        LocalDate from = ym.atDay(1), to = ym.atEndOfMonth();
        if (from.isAfter(today)) return List.of();
        List<Service.BankDay> out = new ArrayList<>(ym.lengthOfMonth());
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            boolean falta = fl.contains(d);
            boolean normal = nm.contains(d);
            String off = calendar.off(d, extraOff);
            String kind = falta ? "falta"
                       : off.isEmpty() || normal ? "work"
                       : off.startsWith("weekend") ? "weekend"
                       : off.startsWith("holiday:") ? "holiday"
                       : off.startsWith("optional:") ? "optional" : "off";
            out.add(new Service.BankDay(d, mins.getOrDefault(d, 0L), falta, normal, kind));
        }
        return List.copyOf(out);
    }

    public List<Service.MonthSaldo> history(YearMonth ym, int n) {
        synchronized (lock) {
            LocalDate today = Time.today();
            LocalDate start = ym.minusMonths(Math.max(0, n - 1)).atDay(1);
            LocalDate end = ym.atEndOfMonth().isAfter(today) ? today : ym.atEndOfMonth();
            Map<LocalDate, Long> mins = start.isAfter(end) ? Map.of() : dayMins(repo.between(start, end));
            Repo.DayFlags flags = repo.allFlags();
            return computeHistory(ym, n, today, dailyMinutes(), mins,
                    new HashSet<>(flags.faltas()), new HashSet<>(flags.normais()), calendar.extraDays());
        }
    }

    private List<Service.MonthSaldo> computeHistory(YearMonth ym, int n, LocalDate today, int daily,
                                                    Map<LocalDate, Long> mins, Set<LocalDate> fl, Set<LocalDate> nm,
                                                    Set<LocalDate> extraOff) {
        List<Service.MonthSaldo> out = new ArrayList<>(n);
        for (int i = n - 1; i >= 0; i--) {
            YearMonth m = ym.minusMonths(i);
            out.add(new Service.MonthSaldo(m, computeSaldoBetween(m.atDay(1), m.atEndOfMonth(), today, daily, mins, fl, nm, extraOff)));
        }
        return List.copyOf(out);
    }

    /** Complete bank + month days + 12-month history in just 2 SQL queries (down from ~450+). */
    public BankSnapshot bankSnapshot(YearMonth ym, int historyMonths) {
        synchronized (lock) {
            LocalDate today = Time.today();
            LocalDate start = ym.minusMonths(Math.max(0, historyMonths - 1)).atDay(1);
            LocalDate to = ym.atEndOfMonth();
            Map<LocalDate, Long> mins = dayMins(repo.between(start, to));
            Repo.DayFlags flags = repo.allFlags();
            Set<LocalDate> fl = new HashSet<>(flags.faltas());
            Set<LocalDate> nm = new HashSet<>(flags.normais());
            Set<LocalDate> extraOff = calendar.extraDays();
            int daily = dailyMinutes();
            Service.Bank b = computeBank(ym, today, daily, mins, fl, nm, extraOff);
            List<Service.BankDay> days = computeBankDays(ym, today, mins, fl, nm, extraOff);
            List<Service.MonthSaldo> months = computeHistory(ym, historyMonths, today, daily, mins, fl, nm, extraOff);
            return new BankSnapshot(b, days, months);
        }
    }

    public boolean falta(LocalDate d) { synchronized (lock) { return repo.flagOn(d, "falta"); } }
    public void falta(LocalDate d, boolean on) {
        synchronized (lock) {
            if (on) repo.flagSet(d, "falta");
            else repo.flagClear(d, "falta");
        }
    }
    public List<LocalDate> faltas() { synchronized (lock) { return repo.flags("falta", "%"); } }
    public boolean normal(LocalDate d) { synchronized (lock) { return repo.flagOn(d, "normal"); } }
    public void normal(LocalDate d, boolean on) {
        synchronized (lock) {
            if (on) repo.flagSet(d, "normal");
            else repo.flagClear(d, "normal");
        }
    }
    public List<LocalDate> normais() { synchronized (lock) { return repo.flags("normal", "%"); } }

    public String normalWarn(LocalDate d) {
        synchronized (lock) {
            if (repo.flagOn(d, "falta")) return "o dia também tem falta — a falta ganha";
            if (calendar.off(d).isEmpty()) return "o dia não é dia off (sem efeito)";
            return "batidas pagam a jornada (não somam banco)";
        }
    }

    public String faltaWarn(LocalDate d) {
        synchronized (lock) {
            String off = calendar.off(d);
            if (!off.isEmpty()) return "dia off (" + off + ")   ·   a falta deduz a jornada";
            for (Entry e : repo.on(d)) if (e.closed()) return "o dia já tem batidas (a falta não muda o saldo)";
            return "";
        }
    }

    private List<Entry> rows(String q, String filter) {
        if (q != null && !q.isBlank()) {
            var matches = repo.search(q);
            if (filter == null || filter.isBlank()) return matches;
            String pattern = Parse.like(filter).pattern();
            return matches.stream().filter(e -> like(pattern, e.dia())).toList();
        }
        if (filter != null && !filter.isBlank()) return repo.byDay(Parse.like(filter).pattern());
        return repo.all();
    }

    public String holiday(LocalDate d) {
        synchronized (lock) { return calendar.off(d); }
    }

    public Report report(LocalDate from, LocalDate to) {
        synchronized (lock) {
            LocalDate today = Time.today();
            String plano = (!from.isAfter(today) && !to.isBefore(today)) ? bank().planLabel() : "";
            return reports.create(from, to, saldoBetween(from, to), plano);
        }
    }

    public Map<String, Object> dayInfo(LocalDate dia) {
        synchronized (lock) {
            List<Entry> rows = repo.on(dia);
            long mins = 0;
            for (Entry e : rows) if (e.min() > 0) mins += e.min();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dia", Str.d(dia));
            m.put("diaIso", Str.iso(dia));
            m.put("d3", Str.d3(dia));
            m.put("off", calendar.off(dia));
            m.put("falta", repo.flagOn(dia, "falta"));
            m.put("normal", repo.flagOn(dia, "normal"));
            m.put("mins", mins);
            m.put("dur", Dur.hm(mins));
            m.put("hours", Dur.dec(mins));
            m.put("dailyMins", dailyMinutes());
            m.put("entries", rows);
            return m;
        }
    }
}
