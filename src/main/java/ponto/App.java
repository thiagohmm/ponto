package ponto;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * O programa inteiro: um menu compacto, uma tecla por ação.
 * Nada nunca tem que ser digitado como "ponto in <date> <time> <justification>" —
 * teclas são caracteres single e o valor atual é sempre o default.
 */
final class App {

    private final Db db;
    private final Repo repo;
    private final Out out = new Out();
    private final In in;

    App(Db db) { this.db = db; this.repo = new Repo(db); this.in = new In(out); }

    void run() {
        setup();
        Holidays.extra(offList(db.cfg("off")));
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
            default -> out.bad("tecla desconhecida '" + c.key() + "'   ·   ? ajuda");
        }
    }

    private void home() {
        LocalDate today = LocalDate.now();
        long mins = repo.sumMins(today.withDayOfMonth(1), today);
        out.home(who(), repo.count(), mins, db.file.toString(), openNote());
    }

    /**
     * "aberto N" over every day, not just today: a punch left half-done is worth
     * showing even when it is from last month.
     */
    private String openNote() {
        Repo.OpenSum half = repo.openSummary();
        if (half.n() == 0) return "aberto 0";
        LocalDate oldest = half.oldest();
        return "aberto " + half.n()
             + (oldest != null && oldest.isBefore(LocalDate.now().withDayOfMonth(1)) ? "   desde " + Str.d(oldest) : "");
    }

    private void setup() {
        if (db.cfg("asked").equals("1")) return;
        if (!Out.TTY) { db.setCfg("asked", "1"); return; }
        out.title("CONFIGURAÇÃO");
        out.note("db    " + db.file);
        String who = in.text("nome", db.cfg("who"));
        db.setCfg("who", who);
        db.setCfg("asked", "1");
        if (!who.isEmpty()) out.ok("usuário " + who);
    }

    /** O nome nos relatórios; $PONTO_USER seed ele então headless runs have one too. */
    private String who() {
        String saved = db.cfg("who", "");
        return !saved.isEmpty() ? saved : System.getenv("PONTO_USER") == null ? "" : System.getenv("PONTO_USER");
    }

    private void emptyCheck() {
        if (repo.count() == 0) throw new Problema("nenhum registro ainda (1 = novo)");
    }

    // ---- 1 novo -----------------------------------------------------------

    private void novo() {
        out.title("NOVO");
        LocalDate dia = in.date("dia", LocalDate.now());
        offDay(dia, true);
        List<Entry> same = repo.on(dia);

        // o dia que você pick, como it stands right now: every line already on it
        if (!same.isEmpty()) {
            out.note(Str.d(dia) + "   " + same.size() + " linha(s) já neste dia");
            table(same);
            totalLine(minsOf(same), same.size(), openOf(same));
        }

        // uma linha half-open on that day is normally the one you want to close
        List<Entry> open = same.stream().filter(e -> e.tin() != null && e.tout() == null).toList();
        if (open.size() == 1) {
            Entry o = open.getFirst();
            out.warn("aberto: #" + o.id() + "   entrada " + Str.t(o.tin()));
            if (in.yes("fechar #" + o.id() + "?", true)) {
                Optional<LocalTime> t = in.time("saída", Str.t(LocalTime.now()));
                if (t.isPresent()) {
                    Entry done = new Entry(o.id(), o.dia(), o.tin(), t.get(), o.just(), o.crea());
                    check(done, done.id());
                    repo.update(done);
                    out.ok("#" + done.id() + "  " + Str.d(dia) + " " + done.stamp() + "  " + done.dur());
                    return;
                }
            }
        }

        Optional<LocalTime> tin = in.time("entrada", lastOut(same));
        Optional<LocalTime> tout = in.time("saída", punchNow(tin, dia));
        String just = in.text("just", "");

        if (tin.isEmpty() && tout.isEmpty() && just.isEmpty()) { out.warn("nada digitado"); return; }
        if (tout.isPresent() && tin.isEmpty() && !open.isEmpty()) {
            out.warn("#" + open.getFirst().id() + " já está aberto com o mesmo dia");
        }
        Entry fresh = new Entry(0, dia, tin.orElse(null), tout.orElse(null), just, "");
        check(fresh, 0);

        int id = repo.insert(dia, fresh.tin(), fresh.tout(), just);
        Entry made = repo.byId(id);
        out.ok("#" + id + "  " + Str.d(dia) + "  " + made.stamp() + "  " + made.dur());
        if (made.open()) out.warn("linha half: fill the other time later (2 = editar)");
    }

    /** Default for "saída": now, but only when starting a punch today. */
    private String punchNow(Optional<LocalTime> tin, LocalDate dia) {
        return tin.isPresent() && dia.equals(LocalDate.now()) ? Str.t(LocalTime.now()) : "";
    }

    /** Default for the next "entrada": the latest "saída" of that day. */
    private String lastOut(List<Entry> same) {
        LocalTime best = null;
        for (Entry e : same) if (e.tout() != null && (best == null || e.tout().isAfter(best))) best = e.tout();
        return best == null ? "" : Str.t(best);
    }

    // ---- 2 editar ----------------------------------------------------------

    private void edit(int id) {
        out.title("EDITAR");
        if (id <= 0) table(repo.recent(12, 0));
        Entry e = repo.byId(id > 0 ? id : in.num("id", 0));

        out.note("#" + e.id() + "  " + Str.d(e.dia()) + "  " + e.stamp() + "  " + Str.cut(e.just(), 44));
        out.note("Enter mantiene   ·   - limpa");

        LocalDate dia = patchDate(in.field("dia", Str.d(e.dia())), e.dia());
        offDay(dia, !dia.equals(e.dia()));
        LocalTime tin = patchTime(in.field("entrada", Str.t(e.tin())), e.tin());
        LocalTime tout = patchTime(in.field("saída", Str.t(e.tout())), e.tout());
        String just = patchText(in.field("just", e.just()), e.just());

        Entry now = new Entry(e.id(), dia, tin, tout, just, e.crea());
        if (now.open() && now.just().isEmpty()) throw new Bad("uma linha half precisa de justificativa");
        if (now.closed() && now.min() == 0) throw new Bad("entrada = saída");
        check(now, now.id());

        if (same(e, now)) { out.note("sem mudança"); return; }
        if (tin == null && tout == null) repo.clearTimes(now); else repo.update(now);
        out.done(true, "#" + now.id() + "  " + Str.d(dia) + "  " + now.stamp() + "  " + now.dur());
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

    /** cfg `off` = days you call off yourself: "2026-10-27 2026-12-31". */
    private List<LocalDate> offList(String raw) {
        List<LocalDate> r = new ArrayList<>();
        for (String s : raw.split("[;, ]+")) {
            if (s.isBlank()) continue;
            try {
                r.add(Parse.date(s));
            } catch (Bad b) {
                out.warn("folga: '" + Str.cut(s, 24) + "'   ·   não uma data, ignorado");
            }
        }
        return r;
    }

    private LocalDate patchDate(In.Val v, LocalDate cur) {
        for (;;) {
            if (v.keep()) return cur;
            try { return Parse.date(v.raw()); }
            catch (Bad b) { out.bad(b.getMessage()); v = in.field("dia", Str.d(cur)); }
        }
    }

    private LocalTime patchTime(In.Val v, LocalTime cur) {
        for (;;) {
            if (v.keep()) return cur;
            if (v.clear()) return null;
            try { return Parse.time(v.raw()); }
            catch (Bad b) { out.bad(b.getMessage()); v = in.field("hora", Str.t(cur)); }
        }
    }

    private String patchText(In.Val v, String cur) { return v.keep() ? cur : v.value(cur); }

    private static boolean same(Entry a, Entry b) {
        return a.dia().equals(b.dia()) && a.tin() == b.tin() && a.tout() == b.tout() && a.just().equals(b.just());
    }

    /** Midnight + overlap guard (shared by new and edit; `skip` excludes the row itself). */
    private void check(Entry e, int skip) {
        if (!e.closed()) return;
        int a = Str.tod(e.tin()), b = Str.tod(e.tout());
        if (a == b) throw new Bad("entrada = saída");
        if (b < a && !in.yes("cruza meia-noite (" + Dur.hm(b - a + 1440) + ")?", true)) throw new Problema("cancelado");
        int bb = b < a ? b + 1440 : b;

        for (Entry o : repo.on(e.dia())) {
            if (o.id() == skip || !o.closed()) continue;
            int x = Str.tod(o.tin()), y = Str.tod(o.tout());
            if (y <= x) y += 1440;
            if (bb <= x || a >= y) continue;
            if (!in.yes("overlaps #" + o.id() + " " + o.stamp() + " (" + o.dur() + ")?", false)) throw new Problema("cancelado");
            return;
        }
    }

    // ---- 3 excluir --------------------------------------------------------

    /**
     * Two ways to delete, asked up front: a whole day or specific ids. Either way
     * the rows are shown with their total, confirmed once, and the menu comes back.
     *
     *   3              asks: all day or id
     *   3 7          3 5 6 8        ids straight away
     *   3 01/10      3 a          a whole day
     */
    private void del(In.Cmd c) {
        out.title("EXCLUIR");
        if (!c.ids().isEmpty()) { delIds(c.ids()); return; }

        String rest = c.rest().toLowerCase();
        if (rest.startsWith("a") || rest.startsWith("d")) { delDay(in.date("dia", defaultDay())); return; }
        LocalDate d = null;
        if (!rest.isEmpty()) { try { d = Parse.date(rest); } catch (Bad ignored) { } }
        if (d != null) { delDay(d); return; }

        if (in.choice("dia todo ou id", "ai", 'a') == 'a') delDay(in.date("dia", defaultDay()));
        else delIds(in.ids("id(s)", 0));
    }

    /** A whole day at once: every line of it goes, nothing else is touched. */
    private void delDay(LocalDate d) {
        List<Entry> rows = repo.on(d);
        if (rows.isEmpty()) { out.note("nada em " + Str.d(d)); return; }
        out.note(Str.d(d) + "   " + rows.size() + " linha(s)");
        table(rows);
        totalLine(minsOf(rows), rows.size(), openOf(rows));
        if (!in.yes("excluir todas " + rows.size() + " de " + Str.d(d) + "?", false)) { out.note("cancelado"); return; }
        int n = repo.deleteDay(d);
        out.ok("excluídas " + n + "   " + Str.d(d) + "   dia está vazio agora");
    }

    /** One or many ids; the ones that are not found are named, the rest go. */
    private void delIds(List<Integer> ids) {
        List<Entry> hits = new ArrayList<>();
        List<Integer> missing = new ArrayList<>();
        for (int id : ids) {
            try { hits.add(repo.byId(id)); } catch (Problema ignored) { missing.add(id); }
        }
        if (hits.isEmpty()) { out.bad("nenhum registro: " + ids); return; }
        table(hits);
        totalLine(minsOf(hits), hits.size(), openOf(hits));
        if (!in.yes("excluir " + hits.size() + " linha(s)?", false)) { out.note("cancelado"); return; }
        for (Entry h : hits) repo.delete(h.id());
        out.ok("excluídas " + hits.size() + (missing.isEmpty() ? "" : "   não encontradas: " + missing));
    }

    /** Today if it has lines, otherwise the last day that has any. */
    private LocalDate defaultDay() {
        LocalDate today = LocalDate.now();
        return repo.countOn(today) > 0 ? today : nzD(repo.max(), today);
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

    // ---- 4 listar / d por data / 6 buscar ----------------------------------

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
            out.note("mostradas " + shown + " de " + total);
            if (shown >= total || !in.yes("antigo", false)) return;
        }
    }

    /**
     * `4` the newest records · `4 50` how many · `4 2026`, `4 10/2026`,
     * `4 oct`, `4 01/10/2026` every record of that year, month or day.
     */
    private void list(In.Cmd c) {
        out.title("LISTAR");
        String rest = c.rest().strip();
        if (rest.isEmpty()) { page(repo.count(), c.arg() > 0 ? c.arg() : 20, (n, s) -> repo.recent(n, s)); return; }
        if (countOnly(rest)) { page(repo.count(), Integer.parseInt(rest), (n, s) -> repo.recent(n, s)); return; }
        Parse.Like f;
        try { f = Parse.like(rest); } catch (Bad e) { out.bad(e.getMessage()); return; }
        byFilter(f);
    }

    /** `d` — all the records of a day, month or year, asked for. */
    private void byDate(String rest) {
        out.title("POR DATA");
        String ask = rest.strip();
        if (ask.isEmpty()) ask = in.text("dia, mês ou ano", "");
        if (ask.isEmpty()) { out.note("digite 2026 · 10/2026 · out · 01/10/2026"); return; }
        Parse.Like f;
        try { f = Parse.like(ask); } catch (Bad e) { out.bad(e.getMessage()); return; }
        byFilter(f);
    }

    /** Everything whose day matches the pattern, paged, with the running total. */
    private void byFilter(Parse.Like f) {
        int total = repo.countDay(f.pattern());
        out.note("correspondendo  " + f.label() + "   ·   " + total + " registro(s)");
        if (total == 0) { out.note("nada em " + f.label()); return; }
        page(total, 20, (n, s) -> repo.byDay(f.pattern(), n, s));
    }

    /** A bare 1..999 after `4` means "how many rows"; a 4-digit year is a date. */
    private static boolean countOnly(String s) {
        if (s.isEmpty() || s.length() > 3 || !s.chars().allMatch(ch -> ch >= '0' && ch <= '9')) return false;
        return Integer.parseInt(s) >= 1;
    }

    /** One total line: "h:mm" and decimal hours, the same pair the reports print. */
    private void totalLine(long mins, int lines, int half) {
        out.note("total  " + Dur.hm(mins) + "   " + Str.n(mins / 60.0) + " h   ·   " + lines + " linha(s)"
                 + (half > 0 ? "   ·   " + half + " incompleta(s), não contam" : ""));
    }

    private void find(String rest) {
        out.title("BUSCAR");
        String q = rest.isEmpty() ? in.text("texto", "") : rest;
        if (q.isEmpty()) { out.note("digite algum texto"); return; }
        List<Entry> rows = repo.search(q);
        if (rows.isEmpty()) { out.note("nenhum match para '" + q + "'"); return; }
        table(rows);
        out.note("'" + q + "' -> " + rows.size() + " linhas  ·  " + Dur.hm(totalOf(rows)));
    }

    private static final int JUST_W = 31;   // justification column; the full text follows below
    private static final int[] COL_W = {5, 10, 3, 5, 5, 5, JUST_W};

    private void table(List<Entry> rows) {
        String head = cols("ID", "DATA", "DIA", "ENTRADA", "SAÍDA", "DUR", "JUSTIFICATIVA");
        out.note(head);
        out.note("-".repeat(head.length()));
        for (Entry e : rows) {
            out.note(cols("" + e.id(), Str.d(e.dia()), Str.d3(e.dia()), Str.t(e.tin()), Str.t(e.tout()),
                         e.durCol(), Str.cut(e.just(), JUST_W)));
            // the table column is narrow, but the text must never be lost: every
            // cut justification follows its line, word-wrapped ("· " = "continuação")
            if (e.just().length() > JUST_W)
                for (String l : Str.wrap(e.just(), 74)) out.note("· " + l);
        }
    }

    private static String cols(String... v) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length; i++) b.append(i > 0 ? "  " : "").append(Str.fix(v[i], COL_W[i]));
        return b.toString();
    }

    // ---- 5 soma -----------------------------------------------------------

    private void sum() {
        out.title("SOMA");
        emptyCheck();
        Period p = in.period("período", repo.min(), repo.max());
        Report r = report(p);
        out.note(Str.fix("DATA", 12) + Str.fix("DIA", 6) + Str.fix("LINHAS", 8) + Str.fix("DURAÇÃO", 12));
        out.note("-".repeat(38));
        for (Report.Day d : r.summary()) {
            out.note(Str.fix(Str.d(d.dia()), 12) + Str.fix(d.d3(), 6) + Str.fix("" + d.lines(), 8) + Str.fix(Dur.hm(d.mins()), 12));
        }
        out.note("-".repeat(38));
        out.note(boldRow("TOTAL", r));
        if (r.openLines() > 0) out.warn(r.openLines() + " linha(s) incompleta(s) não contam");
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
        out.title("EXPORTAR " + kind.toUpperCase());
        emptyCheck();
        Period p = in.period("período", repo.min(), repo.max());
        List<Entry> rows = repo.between(p.from(), p.to());
        if (rows.isEmpty()) { out.bad("nada entre " + p.label()); return; }

        Report r = new Report(new Report.Head(who(), db.file.toString(), p, stamp()), rows);
        Path dir = Path.of(System.getProperty("user.dir"));
        String base = Ver.NAME + "-" + Str.iso(p.from()) + "_" + Str.iso(p.to()) + "." + kind;
        String typed = in.text("arquivo", dir.resolve(base).toString());
        Path target = Path.of(typed);
        if (!target.isAbsolute()) target = dir.resolve(target);
        String name = target.getFileName().toString();
        if (!name.toLowerCase().endsWith("." + kind)) target = Path.of(target + "." + kind);

        if (target.toFile().exists() && !in.yes("overwrite " + target.getFileName() + "?", true)) {
            out.note("cancelado");
            return;
        }

        long bytes = switch (kind) {
            case "csv" -> Csv.write(target, r);
            case "xls" -> Xls.write(target, r);
            default -> Pdf.write(target, r);
        };
        out.ok(target + "   " + human(bytes) + "   " + r.lines().size() + " linhas   " + r.totalLabel());
        if (in.yes("open it?", true)) openWith(target);
    }

    private Report report(Period p) {
        return new Report(new Report.Head(who(), db.file.toString(), p, stamp()), repo.between(p.from(), p.to()));
    }

    private static String human(long n) {
        if (n < 1024) return n + " B";
        if (n < 1048576) return String.format(Locale.ROOT, "%.1f KB", n / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", n / 1048576.0);
    }

    private String stamp() {
        LocalDateTime n = LocalDateTime.now();
        return Str.d(n.toLocalDate()) + " " + Str.t(n.toLocalTime());
    }

    private void openWith(Path p) {
        String tool = System.getProperty("os.name", "").startsWith("Mac") ? "open" : "xdg-open";
        try {
            ProcessBuilder pb = new ProcessBuilder(tool, p.toString());
            pb.redirectErrorStream(true);
            pb.inheritIO();
            pb.start();
        } catch (java.io.IOException e) { out.warn("não pode abrir: " + e.getMessage()); }
    }

    // ---- c config --------------------------------------------------------

    private void config() {
        out.title("CONFIGURAÇÃO");
        out.note("db      " + db.file);
        out.note("usuário    " + (who().isEmpty() ? "-" : who()));
        out.note("registros " + repo.count() + "   " + range());
        out.note("csv     sep '" + Csv.SEP + "'  (PONTO_CSV_SEP)");
        out.note("java    " + System.getProperty("java.version") + "   sqlite " + db.version());
        out.note("folga     " + (db.cfg("off").isEmpty() ? "-" : db.cfg("off")));
        for (String s : Holidays.next(4)) out.note("dia de folga " + s);
        String now = who();
        String who = in.text("usuário", now.isEmpty() ? "-" : now);
        db.setCfg("who", who.equals("-") ? "" : who);
        out.ok("usuário " + (who.equals("-") ? "-" : who));
        String off = in.text("folga", db.cfg("off").isEmpty() ? "-" : db.cfg("off"));
        db.setCfg("off", off.equals("-") ? "" : off);
        Holidays.extra(offList(off));
        out.ok("folga " + (off.equals("-") ? "-" : Holidays.extra().size() + " dia(s)"));
        if (in.yes("vacuum the db?", false)) { db.vacuum(); out.ok("vacuumed"); }
    }

    private String range() {
        LocalDate a = repo.min(), b = repo.max();
        return a == null ? "-" : Str.d(a) + " .. " + Str.d(b);
    }

    private void help() {
        out.title("TECLAS");
        out.note("1 novo   2 editar [id]   3 excluir [id id.. | a | dd/mm]   4 listar [n]");
        out.note("5 soma   6 buscar [texto]   7 pdf   8 xls   9 csv   d por data");
        out.note("c config   x limpar tela   ? ajuda   0 sair");
        out.note("por data  2026 (ano) · 10/2026 · out · out 2026 · 01/10/2026 · hoje");
        out.note("         4 2026 works the same way as d 2026");
        out.note("datas  08/10 · 08/10/2026 · 2026-10-08 · 5out · hoje · y · -3");
        out.note("horas  07:30 · 07h30 · 0730 · 8 · agora          '-' limpa um campo");
        out.note("db     " + db.file + "   (PONTO_DB / --db=path)");
    }
}
