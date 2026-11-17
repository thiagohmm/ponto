package ponto.controller;

import ponto.Bad;
import ponto.Eof;
import ponto.Problema;
import ponto.Ver;
import ponto.model.Dur;
import ponto.model.Entry;
import ponto.model.Parse;
import ponto.model.Period;
import ponto.model.Report;
import ponto.model.VaultCrypto;
import ponto.model.VaultGen;
import ponto.model.VaultItem;
import ponto.model.VaultMeta;
import ponto.model.VaultPayload;
import ponto.model.VaultRewrap;
import ponto.model.VaultDraft;
import ponto.app.Service;
import ponto.model.Str;
import ponto.view.Cols;
import ponto.view.Csv;
import ponto.view.In;
import ponto.view.Out;
import ponto.view.Pdf;
import ponto.view.VaultFile;
import ponto.view.Xls;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;

import javax.crypto.SecretKey;

/**
 * The whole program: one compact menu, one key per action.
 * Nothing ever has to be typed as "ponto in <date> <time> <justification>" —
 * keys are single characters and the current value is always the default.
 */
public final class App {

    private final Service svc;
    private final Out out = new Out();
    private final In in;

    /** The vault's key, while the cofre is open. Null the moment it is locked. */
    private SecretKey cofreKey;

    public App(Service svc) { this.svc = svc; this.in = new In(out); }

    public void run() {
        setup();
        svc.registerOff(s -> out.warn("folga: '" + Str.cut(s, 24) + "'   ·   não é data, ignorado"));
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
            case "f" -> falta(c);
            case "v" -> cofre(c);
            case "x" -> out.clear();
            case "?" -> help();
            default -> out.bad("tecla desconhecida '" + c.key() + "'   ·   ? ajuda");
        }
    }

    private void home() {
        var h = svc.home();
        String openNote = h.open() == 0 ? "abertos 0"
                : "abertos " + h.open() + (h.openSince().isEmpty() ? "" : "   desde " + h.openSince());
        String plan = h.planLabel();
        String saldoNote = (h.saldoMins() < 0 ? "! banco negativo " : "saldo do mês ") + h.saldo()
                + (plan.isEmpty() ? "" : "   ·   plano " + plan);
        String who = h.who();
        String company = h.company();
        String label = company == null || company.isEmpty() ? who
                : who.isEmpty() ? company : who + "  ·  " + company;
        out.home(label, h.records(), h.monthMins(), h.db(), openNote, saldoNote);
    }

    private void setup() {
        if (svc.asked()) return;
        if (!Out.TTY) { svc.markAsked(); return; }
        out.title("CONFIGURAÇÃO");
        out.note("banco " + svc.dbPath());
        String who = in.text("nome", svc.rawWho());
        svc.setConfig(who, null, false);
        if (!who.isEmpty()) out.ok("usuário " + who);
    }

    private void emptyCheck() {
        if (svc.count() == 0) throw new Problema("ainda sem registros (1 = novo)");
    }

    // ---- 1 new -----------------------------------------------------------

    private void novo() {
        out.title("NOVO");
        LocalDate dia = in.date("dia", ponto.model.Time.today());
        offDay(dia, true);
        List<Entry> same = svc.on(dia);

        // the day you picked, as it stands right now: every line already on it
        if (!same.isEmpty()) {
            out.note(Str.d(dia) + "   " + same.size() + " linha(s) já neste dia");
            table(same);
            totalLine(minsOf(same), same.size(), openOf(same));
        }

        // a half-open line on that day is normally the one you want to close
        List<Entry> open = same.stream().filter(e -> e.tin() != null && e.tout() == null).toList();
        if (open.size() == 1) {
            Entry o = open.getFirst();
            out.warn("aberto: #" + o.id() + "   entrada " + Str.t(o.tin()));
            if (in.yes("fechar #" + o.id() + "?", true)) {
                Optional<LocalTime> t = in.time("saída", Str.t(ponto.model.Time.now()));
                if (t.isPresent()) {
                    Entry done = new Entry(o.id(), o.dia(), o.tin(), t.get(), o.just(), o.crea());
                    check(done, done.id());
                    Entry saved = svc.update(done.id(), done.dia(), done.tin(), done.tout(), done.just(), true, true);
                    out.ok("#" + done.id() + "  " + Str.d(dia) + " " + saved.stamp() + "  " + saved.dur());
                    return;
                }
            }
        }

        Optional<LocalTime> tin = in.time("entrada", lastOut(same));
        Optional<LocalTime> tout = in.time("saída", punchNow(tin, dia));
        String just = in.text("justificativa", "");

        if (tin.isEmpty() && tout.isEmpty() && just.isEmpty()) { out.warn("nada digitado"); return; }
        if (tout.isPresent() && tin.isEmpty() && !open.isEmpty()) {
            out.warn("#" + open.getFirst().id() + " já está aberto no mesmo dia");
        }
        Entry fresh = new Entry(0, dia, tin.orElse(null), tout.orElse(null), just, "");
        check(fresh, 0);

        Entry made = svc.create(dia, fresh.tin(), fresh.tout(), just, false, true, true);
        out.ok("#" + made.id() + "  " + Str.d(dia) + "  " + made.stamp() + "  " + made.dur());
        if (made.open()) out.warn("linha incompleta: preencha a outra hora depois (2 = editar)");
    }

    /** Default for "out": now, but only when starting a punch today. */
    private String punchNow(Optional<LocalTime> tin, LocalDate dia) {
        return tin.isPresent() && dia.equals(ponto.model.Time.today()) ? Str.t(ponto.model.Time.now()) : "";
    }

    /** Default for the next "in": the latest "out" of that day. */
    private String lastOut(List<Entry> same) {
        LocalTime best = null;
        for (Entry e : same) if (e.tout() != null && (best == null || e.tout().isAfter(best))) best = e.tout();
        return best == null ? "" : Str.t(best);
    }

    // ---- 2 edit ----------------------------------------------------------

    private void edit(int id) {
        out.title("EDITAR");
        if (id <= 0) table(svc.recent(12, 0));
        Entry e = svc.byId(id > 0 ? id : in.num("id", 0));

        out.note("#" + e.id() + "  " + Str.d(e.dia()) + "  " + e.stamp() + "  " + Str.cut(e.just(), 44));
        out.note("Enter mantém   ·   - limpa");

        LocalDate dia = patchDate(in.field("dia", Str.d(e.dia())), e.dia());
        offDay(dia, !dia.equals(e.dia()));
        LocalTime tin = patchTime(in.field("entrada", Str.t(e.tin())), e.tin());
        LocalTime tout = patchTime(in.field("saída", Str.t(e.tout())), e.tout());
        String just = patchText(in.field("justificativa", e.just()), e.just());

        Entry now = new Entry(e.id(), dia, tin, tout, just, e.crea());
        if (now.open() && now.just().isEmpty()) throw new Bad("linha incompleta precisa de justificativa");
        if (now.closed() && now.min() == 0) throw new Bad("entrada = saída");
        check(now, now.id());

        if (same(e, now)) { out.note("inalterado"); return; }
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
        String off = svc.holiday(d);
        if (!off.isEmpty()) out.warn(Str.d(d) + "  " + Str.d3(d) + "   " + off);
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
        Service.Warn w = svc.inspect(e, skip);
        if (w.sameTime()) throw new Bad("entrada = saída");
        if (w.midnight() && !in.yes("cruza meia-noite (" + w.midnightLabel() + ")?", true))
            throw new Problema("cancelado");
        if (w.overlap() != null) {
            Entry o = w.overlap();
            if (!in.yes("sobreposto a #" + o.id() + " " + o.stamp() + " (" + o.dur() + ")?", false))
                throw new Problema("cancelado");
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
        out.title("APAGAR");
        if (!c.ids().isEmpty()) { delIds(c.ids()); return; }

        String rest = c.rest().toLowerCase();
        if (rest.startsWith("a") || rest.startsWith("d")) { delDay(in.date("dia", defaultDay())); return; }
        LocalDate d = null;
        if (!rest.isEmpty()) { try { d = Parse.date(rest); } catch (Bad ignored) { } }
        if (d != null) { delDay(d); return; }

        if (in.choice("dia inteiro ou id", "ai", 'a') == 'a') delDay(in.date("dia", defaultDay()));
        else delIds(in.ids("id(s)", 0));
    }

    /** A whole day at once: every line of it goes, nothing else is touched. */
    private void delDay(LocalDate d) {
        List<Entry> rows = svc.on(d);
        if (rows.isEmpty()) { out.note("nada em " + Str.d(d)); return; }
        out.note(Str.d(d) + "   " + rows.size() + " linha(s)");
        table(rows);
        totalLine(minsOf(rows), rows.size(), openOf(rows));
        if (!in.yes("apagar todos os " + rows.size() + " de " + Str.d(d) + "?", false)) { out.note("cancelado"); return; }
        int n = svc.deleteDay(d);
        out.ok("apagado " + n + "   " + Str.d(d) + "   o dia está vazio agora");
    }

    /** One or many ids; the ones that are not found are named, the rest go. */
    private void delIds(List<Integer> ids) {
        List<Entry> hits = new ArrayList<>();
        List<Integer> missing = new ArrayList<>();
        for (int id : ids) {
            try { hits.add(svc.byId(id)); } catch (Problema ignored) { missing.add(id); }
        }
        if (hits.isEmpty()) { out.bad("sem registro: " + ids); return; }
        table(hits);
        totalLine(minsOf(hits), hits.size(), openOf(hits));
        if (!in.yes("apagar " + hits.size() + " linha(s)?", false)) { out.note("cancelado"); return; }
        for (Entry h : hits) svc.delete(h.id());
        out.ok("apagado " + hits.size() + (missing.isEmpty() ? "" : "   não encontrado: " + missing));
    }

    /** Today if it has lines, otherwise the last day that has any. */
    private LocalDate defaultDay() {
        LocalDate today = ponto.model.Time.today();
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
            out.note("mostrando " + shown + " de " + total);
            if (shown >= total || !in.yes("anteriores", false)) return;
        }
    }

    /**
     * `4` the newest records · `4 50` how many · `4 2026`, `4 10/2026`,
     * `4 oct`, `4 01/10/2026` every record of that year, month or day.
     */
    private void list(In.Cmd c) {
        out.title("LISTA");
        String rest = c.rest().strip();
        if (rest.isEmpty()) { page(svc.count(), c.arg() > 0 ? c.arg() : 20, (n, s) -> svc.recent(n, s)); return; }
        if (countOnly(rest)) { page(svc.count(), Integer.parseInt(rest), (n, s) -> svc.recent(n, s)); return; }
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
        int total = svc.countDay(f.pattern());
        out.note("coincidência  " + f.label() + "   ·   " + total + " registro(s)");
        if (total == 0) { out.note("nada em " + f.label()); return; }
        page(total, 20, (n, s) -> svc.byDay(f.pattern(), n, s));
    }

    /** A bare 1..999 after `4` means "how many rows"; a 4-digit year is a date. */
    private static boolean countOnly(String s) {
        if (s.isEmpty() || s.length() > 3 || !s.chars().allMatch(ch -> ch >= '0' && ch <= '9')) return false;
        return Integer.parseInt(s) >= 1;
    }

    /** One total line: "h:mm" and decimal hours, the same pair the reports print. */
    private void totalLine(long mins, int lines, int half) {
        out.note("total  " + Dur.hm(mins) + "   " + Str.n(mins / 60.0) + " h   ·   " + lines + " linha(s)"
                 + (half > 0 ? "   ·   " + half + " incompleta(s), fora da soma" : ""));
    }

    private void find(String rest) {
        out.title("BUSCAR");
        String q = rest.isEmpty() ? in.text("texto", "") : rest;
        if (q.isEmpty()) { out.note("digite um texto"); return; }
        List<Entry> rows = svc.search(q);
        if (rows.isEmpty()) { out.note("nenhuma coincidência para '" + q + "'"); return; }
        table(rows);
        out.note("'" + q + "' -> " + rows.size() + " linha(s)  ·  " + Dur.hm(totalOf(rows)));
    }

    private static final int JUST_W = 34;   // justification column; the full text follows below

    private void table(List<Entry> rows) {
        out.note(cols("ID", "DATA", "DIA", "ENTRADA", "SAÍDA", "DURAÇÃO", "JUSTIFICATIVA"));
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
        int[] w = {5, 11, 4, 7, 5, 7, JUST_W};
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length; i++) b.append(i > 0 ? "  " : "").append(Str.fix(v[i], w[i]));
        return b.toString();
    }

    // ---- f falta -----------------------------------------------------------

    /**
     * The whole-day falta flag for the bank: `f` toggles today, `f 03/11`
     * toggles that day, `f lista` lists them. Marking is a warning, never a
     * wall (the same style as the day-off line of `1` and `2`).
     */
    private void falta(In.Cmd c) {
        String rest = c.rest().strip();
        if (rest.toLowerCase().startsWith("l")) {
            out.title("FALTAS");
            List<LocalDate> fl = svc.faltas();
            if (fl.isEmpty()) { out.note("nenhuma falta marcada   ·   f [dia] alterna"); return; }
            for (LocalDate d : fl) {
                String off = svc.holiday(d);
                out.note(Str.d(d) + "  " + Str.d3(d) + (off.isEmpty() ? "" : "   ·   " + off));
            }
            out.note(fl.size() + " falta(s)");
            return;
        }
        LocalDate d = rest.isEmpty() ? ponto.model.Time.today() : Parse.date(rest);
        boolean on = !svc.falta(d);
        String warn = on ? svc.faltaWarn(d) : "";
        svc.falta(d, on);
        out.title("FALTA");
        out.ok("falta " + Str.d(d) + (on ? " marcada" : " retirada"));
        if (!warn.isEmpty()) out.warn(warn);
    }

    // ---- 5 sum -----------------------------------------------------------

    private void sum() {
        out.title("SOMA");
        emptyCheck();
        Period p = in.period("período", svc.min(), svc.max());
        Report r = svc.report(p.from(), p.to());
        out.note(Str.fix("DATA", 12) + Str.fix("DIA", 6) + Str.fix("LINHAS", 8) + Str.fix("DURAÇÃO", 12));
        out.note("-".repeat(38));
        for (Report.Day d : r.summary()) {
            out.note(Str.fix(Str.d(d.dia()), 12) + Str.fix(d.d3(), 6) + Str.fix("" + d.lines(), 8) + Str.fix(Dur.hm(d.mins()), 12));
        }
        out.note("-".repeat(38));
        out.note(boldRow("TOTAL", r));
        if (r.openLines() > 0) out.warn(r.openLines() + " linha(s) incompleta(s), fora da soma");
        out.note("saldo período  " + r.saldoLabel()
                 + (r.faltas() > 0 ? "   ·   faltas: " + r.faltas() : ""));
        if (!r.planoLabel().isEmpty()) out.note("plano  " + r.planoLabel());
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
        Period p = in.period("período", svc.min(), svc.max());
        Report r = svc.report(p.from(), p.to());
        if (r.lines().isEmpty()) { out.bad("nada entre " + p.label()); return; }
        Path dir = Path.of(System.getProperty("user.dir"));
        String base = Ver.NAME + "-" + Str.iso(p.from()) + "_" + Str.iso(p.to()) + "." + kind;
        String typed = in.text("arquivo", dir.resolve(base).toString());
        Path target = Path.of(typed);
        if (!target.isAbsolute()) target = dir.resolve(target);
        String name = target.getFileName().toString();
        if (!name.toLowerCase().endsWith("." + kind)) target = Path.of(target + "." + kind);

        if (target.toFile().exists() && !in.yes("substituir " + target.getFileName() + "?", true)) {
            out.note("cancelado");
            return;
        }

        byte[] bytes = switch (kind) {
            case "csv" -> Csv.write(target, r);
            case "xls" -> Xls.write(target, r);
            default -> Pdf.write(target, r);
        };
        out.ok(target + "   " + human(bytes.length) + "   " + r.lines().size() + " linha(s)   " + r.totalLabel());
        if (in.yes("abrir?", true)) openWith(target);
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
        } catch (java.io.IOException e) { out.warn("não abriu: " + e.getMessage()); }
    }

    // ---- c config --------------------------------------------------------

    private void config() {
        out.title("CONFIG");
        var c = svc.config();
        out.note("banco   " + c.db());
        String who = c.who();
        out.note("usuário " + (who.isEmpty() ? "-" : who));
        var act = svc.activeCompany();
        for (var co : svc.listCompanies())
            out.note((co.id() == act.id() ? "* " : "  ") + co.id() + "  " + co.name());
        out.note("registros " + c.records() + "   " + range(c));
        out.note("csv     sep '" + Csv.SEP + "'  (PONTO_CSV_SEP)");
        out.note("java    " + c.java() + "   postgres " + c.postgres());
        String off = c.off();
        out.note("folgas  " + (off.isEmpty() ? "-" : off));
        String daily = c.daily();
        out.note("jornada " + (daily.isEmpty() ? "8" : daily) + " h/dia");
        for (String s : c.nextOff()) out.note("folga   " + s);
        String pick = in.text("empresa", String.valueOf(act.id()));
        if (!pick.isEmpty() && !pick.equals("-") && !pick.equals(String.valueOf(act.id()))) {
            if (pick.chars().allMatch(Character::isDigit)) {
                svc.activate(Integer.parseInt(pick));
                out.ok("empresa #" + pick);
            } else {
                String name = pick.startsWith("+") ? pick.substring(1) : pick;
                var made = svc.createCompany(name, "", "");
                svc.activate(made.id());
                out.ok("empresa #" + made.id() + "  " + made.name());
            }
        }
        c = svc.config();
        who = c.who();
        off = c.off();
        daily = c.daily();
        String w = in.text("usuário", who.isEmpty() ? "-" : who);
        svc.setConfig(w, null, false);
        out.ok("usuário " + (w.equals("-") ? "-" : w));
        String o = in.text("folgas", off.isEmpty() ? "-" : off);
        svc.setConfig(null, o, false, s -> out.warn("folga: '" + Str.cut(s, 24) + "'   ·   não é data, ignorado"));
        out.ok("folgas " + (o.equals("-") ? "-" : Service.offDates(svc.rawOff()).size() + " dia(s)"));
        String d = in.text("jornada", daily.isEmpty() ? "8" : daily);
        try {
            svc.setConfig(null, null, d, false, s -> { });
            out.ok("jornada " + (d.equals("-") ? "8 (padrão)" : d + " h"));
        } catch (Bad b) { out.bad(b.getMessage()); }
        if (in.yes("otimizar o banco?", false)) { svc.setConfig(null, null, true); out.ok("banco otimizado"); }
    }

    // ---- v cofre ---------------------------------------------------------
    /**
     * The password vault, in the terminal. One master password opens every company's
     * records; the key is derived here, in this process, and is dropped when the cofre
     * is locked or the program ends. Nothing in this section prints a plaintext password
     * unless the user asked for that one line: the list shows the target and a masked
     * secret, and the value of a secret appears only for `v s [id]`.
     *
     * Keys: `v` status · `v l [texto]` list · `v n` new · `v e [id]` edit ·
     * `v c` create it · `v s [id]` show one · `v x [id..]` delete · `v g [n]` generate ·
     * `v b [file]` backup · `v i [file]` import · `v w` forget one company ·
     * `v k` change the master password · `v q` lock.
     *
     * The same envelope the browser writes — PBKDF2-SHA256 + AES-256-GCM, a fresh
     * nonce per write, the AAD binding each blob to its company — so a vault made
     * here opens there and the other way round. That interop is the point, and it is
     * what `VaultFileTest` checks.
     */
    private void cofre(In.Cmd c) {
        String rest = c.rest().strip().toLowerCase();
        if (rest.isEmpty() || rest.startsWith("s") && rest.startsWith("sta")) { cofreStatus(); return; }
        char k = rest.charAt(0);
        if (k == 'q') { lock(); out.ok("cofre locked   ·   nothing left in memory"); return; }
        if (k == 'g') { cofreGen(rest.substring(1).strip()); return; }

        // Only `v c` (create) and `v n` (add the first credential) may seal a vault:
        // both ask for the password twice, so a cofre is never created by accident.
        // Everything else refuses an unsealed vault instead of quietly choosing a master password.
        SecretKey key = unlock(k == 'c' || k == 'n');
        if (key == null) return;
        if (k == 'c') { cofreStatus(); return; }
        if (k == 'v' || k == 'l') { cofreList(key, rest.substring(1).strip()); return; }
        if (k == 'n') { cofreNew(key); return; }
        if (k == 'e') { cofreEdit(key, rest.substring(1).strip()); return; }
        if (k == 's') { cofreShow(key, c); return; }
        if (k == 'x') { cofreDel(key, rest.substring(1)); return; }
        if (k == 'b') { cofreBackup(key, rest.substring(1).strip()); return; }
        if (k == 'i') { cofreImport(key, rest.substring(1).strip()); return; }
        if (k == 'w') { cofreWipe(key); return; }
        if (k == 'k') { cofreRekey(); return; }
        out.bad("cofre: not a key '" + k + "'   ·   v l · v n · v s [id] · v q");
    }

    /** The state of the cofre, without opening anything. */
    private void cofreStatus() {
        out.title("COFRE");
        VaultMeta m = svc.vaultMeta();
        out.note(m.state() + "   ·   " + m.kdf() + "   ·   " + m.iterations() + " interações");
        out.note("selos " + svc.vaultCount() + " credential(s)");
        for (var co : svc.listCompanies()) {
            int n = svc.vaultCount(co.id());
            if (n > 0) out.note("  #" + co.id() + "  " + Str.cut(co.name(), 24) + "   " + n);
        }
        out.note("v l lista   v n novo   v s [id] shows one   v g new password");
        out.note("v c creates   ·   v b [file] backup   v i [file] import   v k master   v q lock");
    }

    /**
     * The key, derived once per cofre session and held only while it is open.
     *
     * An unsealed vault offers to be created: the salt and the verifier blob are made
     * here, in the client, and the server stores only the result. A sealed vault asks
     * for the master password and proves the key by opening the verifier — a wrong
     * answer never reaches the store, because nothing was written and nothing was read.
     */
    private SecretKey unlock(boolean create) {
        if (cofreKey != null) return cofreKey;
        VaultMeta m = svc.vaultMeta();
        if (!m.sealed()) {
            if (!create) { out.warn("cofre nunca creou   ·   v c creates it, v n adds the first"); return null; }
            String master = in.secret("senha mestra (new cofre)");
            if (master.isEmpty()) { out.bad("senha mestra empty"); return null; }
            if (!master.equals(in.secret("again, to be sure"))) { out.bad("not the same   ·   nothing was created"); return null; }
            String salt = VaultCrypto.encode(VaultCrypto.randomSalt());
            SecretKey key = VaultCrypto.deriveKey(master, VaultCrypto.decode(salt), VaultCrypto.ITERATIONS);
            svc.vaultCreate(VaultCrypto.KDF, salt, VaultCrypto.ITERATIONS, VaultCrypto.checkBlob(key));
            cofreKey = key;
            out.ok("cofre created   ·   " + VaultCrypto.ITERATIONS + " interações");
            out.warn("there is no recovery: lose this password and the cofre is empty forever");
            return key;
        }
        String master = in.secret("senha mestra");
        if (master.isEmpty()) { out.bad("senha mestra empty"); return null; }
        SecretKey key = VaultCrypto.deriveKey(master, VaultCrypto.decode(m.salt()), m.iterations());
        if (!VaultCrypto.checkOk(key, m.check())) { out.bad("senha mestra incorrecta"); return null; }
        cofreKey = key;
        out.ok("cofre open   ·   " + svc.vaultCount() + " credential(s)");
        return key;
    }

    /** Drop the key. The records stay sealed in the database; nothing is left here. */
    private void lock() { cofreKey = null; }

    // ---- list / show -------------------------------------------------------

    /** Every credential, masked. The filter runs on the plaintext, after opening. */
    private void cofreList(SecretKey key, String filter) {
        out.title("COFRE · LISTA");
        List<VaultItem> all = svc.vaultItems(0);
        if (all.isEmpty()) { out.note("empty   ·   v n adds the first"); return; }
        out.note(cofreCols("ID", "EMP", "APLICATIVO", "USUARIO", "SENHA"));
        out.note("-".repeat(58));
        String f = filter.toLowerCase();
        int shown = 0;
        for (VaultItem it : all) {
            VaultPayload.Plain p = open(key, it);
            if (!f.isEmpty() && !p.label().toLowerCase().contains(f) && !p.desc().toLowerCase().contains(f)) continue;
            out.note(cofreCols("" + it.id(), "#" + it.companyId(), Str.cut(p.app(), 18), Str.cut(p.user(), 14), mask(p.pass())));
            shown++;
        }
        out.note(shown + " of " + all.size() + "   ·   v s [id] shows one   ·   v q locks");
    }

    /**
     * One secret, on screen, on request. It is printed once and stored nowhere: the
     * terminal keeps what it prints in its own scrollback, which is the user's choice.
     */
    private void cofreShow(SecretKey key, In.Cmd c) {
        int id = c.arg();
        if (id <= 0) { out.bad("v s [id]"); return; }
        VaultItem it = cofreById(id);
        if (it == null) { out.bad("no credential #" + id); return; }
        VaultPayload.Plain p = open(key, it);
        out.title("COFRE · #" + id);
        out.note("aplicativo  " + (p.app().isEmpty() ? "-" : p.app()));
        out.note("usuario     " + (p.user().isEmpty() ? "-" : p.user()));
        out.note("empresa     #" + p.companyId() + "  " + companyName(p.companyId()));
        out.note("descrição   " + (p.desc().isEmpty() ? "-" : p.desc()));
        out.note("senha       " + p.pass());
        out.note(p.pass().length() + " chars   ·   " + strengthLabel(p.pass()));
    }

    // ---- new / edit / delete ------------------------------------------------

    /**
     * A new credential. The password is offered generated: a person typing a password
     * into a terminal has their shell history to think about, so the default here is a
     * random one, printed once. `pass=…` types one instead.
     */
    private void cofreNew(SecretKey key) {
        out.title("COFRE · NOVO");
        String app = in.text("aplicativo", "");
        if (app.strip().isEmpty()) { out.bad("aplicativo empty"); return; }
        String user = in.text("usuario", "");
        String desc = in.text("descrição", "");
        int company = pickCompany(svc.activeCompany().id());
        out.note("rules   " + VaultGen.sets(true, true, true, true, false) + "   ·   " + VaultGen.DEFAULT_LENGTH + " chars");
        String typed = in.text("senha (Enter generates, pass= types)", "");
        String pass = generated(typed);
        VaultPayload.Plain p = new VaultPayload.Plain(app, user, pass, desc, company);
        String bad = tooLong(p);
        if (!bad.isEmpty()) { out.bad(bad); return; }
        VaultItem made = svc.createVaultItem(company, VaultCrypto.encryptText(key, VaultPayload.json(p), VaultCrypto.aadItem(company)));
        out.ok("cofre #" + made.id() + "   " + p.label() + "   ·   " + pass.length() + " chars");
        out.note("the value is not kept here: v s " + made.id() + " shows it again");
    }

    private void cofreEdit(SecretKey key, String rest) {
        out.title("COFRE · EDITAR");
        int id = safeId(rest);
        if (id <= 0) id = in.ids("id", 0).isEmpty() ? 0 : in.ids("id", 0).getFirst();
        if (id <= 0) return;
        VaultItem it = cofreById(id);
        if (it == null) { out.bad("no credential #" + id); return; }
        VaultPayload.Plain old = open(key, it);
        out.note("now   " + old.label() + "   ·   " + mask(old.pass()));
        VaultPayload.Plain p = editFields(old);
        String bad = tooLong(p);
        if (!bad.isEmpty()) { out.bad(bad); return; }
        if (p.equals(old)) { out.note("mantido"); return; }
        try {
            svc.updateVaultItem(id, VaultCrypto.encryptText(key, VaultPayload.json(p), VaultCrypto.aadItem(p.companyId())), it.revision());
            out.ok("cofre #" + id + "   " + p.label());
        } catch (ponto.Conflict e) {
            out.bad("outro acesso selou this record   ·   v l, then v e " + id + " again");
        }
    }

    /** The fields, with the current value as the default. Enter keeps, '-' clears. */
    private VaultPayload.Plain editFields(VaultPayload.Plain old) {
        String app = in.field("aplicativo", old.app()).value(old.app());
        String user = in.field("usuario", old.user()).value(old.user());
        String desc = in.field("descrição", old.desc()).value(old.desc());
        int company = pickCompany(old.companyId());
        String typed = in.text("senha (Enter keeps, g regenerates, pass= types)", "");
        return new VaultPayload.Plain(app, user, editedPass(typed, old.pass()), desc, company);
    }

    /** Delete one or many, after showing what is about to go. */
    private void cofreDel(SecretKey key, String rest) {
        out.title("COFRE · APAGAR");
        List<Integer> ids = new ArrayList<>();
        for (String t : rest.split("\\s+")) {
            int id = safeId(t);
            if (id > 0) ids.add(id);
        }
        if (ids.isEmpty()) ids = in.ids("id(s)", 0);
        if (ids.isEmpty()) return;
        List<VaultItem> hits = new ArrayList<>();
        List<Integer> missing = new ArrayList<>();
        for (int id : ids) {
            VaultItem it = cofreById(id);
            if (it == null) missing.add(id); else hits.add(it);
        }
        if (hits.isEmpty()) { out.bad("no credential: " + ids); return; }
        for (VaultItem h : hits) out.note("  #" + h.id() + "  " + open(key, h).label());
        if (!in.yes("apagar " + hits.size() + " credential(s)?", false)) { out.note("cancelado"); return; }
        for (VaultItem h : hits) svc.deleteVaultItem(h.id());
        out.ok("apagado " + hits.size() + (missing.isEmpty() ? "" : "   not found: " + missing));
    }

    /** One company's credentials, gone. The others keep theirs. */
    private void cofreWipe(SecretKey key) {
        out.title("COFRE · FORGET A COMPANY");
        var co = svc.activeCompany();
        List<VaultItem> mine = svc.vaultItems(co.id());
        if (mine.isEmpty()) { out.note("nothing for " + co.name()); return; }
        out.warn(mine.size() + " credential(s) of " + co.name() + " will be gone");
        for (VaultItem it : mine) out.note("  #" + it.id() + "  " + open(key, it).label());
        if (!in.yes("apagar all " + mine.size() + " of " + co.name() + "?", false)) { out.note("cancelado"); return; }
        if (!in.yes("sure? there is no undo", false)) { out.note("cancelado"); return; }
        out.ok("apagado " + svc.deleteVaultCompany(co.id()) + "   ·   " + co.name());
    }

    // ---- backup / import ----------------------------------------------------

    /**
     * A backup file: the whole list under one seal, so a file that was edited refuses
     * to open. The header carries the salt and the iteration count on purpose — they are
     * public parameters, and carrying them is what lets a backup taken before a re-key
     * still open with the same master password.
     */
    private void cofreBackup(SecretKey key, String path) {
        out.title("COFRE · BACKUP");
        List<VaultItem> all = svc.vaultItems(0);
        if (all.isEmpty()) { out.note("nothing to write"); return; }
        List<VaultFile.Open> items = new ArrayList<>();
        for (VaultItem it : all) items.add(new VaultFile.Open(open(key, it), it.revision()));
        VaultMeta m = svc.vaultMeta();
        if (path.isEmpty()) path = VaultFile.fileName(Instant.now());
        try {
            java.nio.file.Files.writeString(Path.of(path), VaultFile.write(key, items, m.salt(), m.iterations()), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) { out.bad("not written: " + path + "   ·   " + e.getMessage()); return; }
        out.ok(path + "   ·   " + items.size() + " record(s)   ·   sealed");
        out.warn("the file is only as safe as where you keep it");
    }

    /**
     * Import a backup. Every record is re-sealed here with the live key before anything
     * is written, so a file from before a re-key still imports. One company that does
     * not exist aborts the whole file instead of importing half of it.
     */
    private void cofreImport(SecretKey key, String path) {
        out.title("COFRE · IMPORT");
        if (path.isEmpty()) path = in.text("file", "");
        if (path.isEmpty()) { out.bad("v i [file]"); return; }
        String text;
        try { text = java.nio.file.Files.readString(Path.of(path), StandardCharsets.UTF_8); }
        catch (java.io.IOException e) { out.bad("not readable: " + path); return; }
        String master = in.secret("senha of this file");
        List<VaultPayload.Plain> plains;
        // Bad, not Problema: the app's "bad input" exception is a sibling of Problema,
        // so catching only Problema here would crash the terminal on a broken file
        try { plains = VaultFile.read(text, master); }
        catch (RuntimeException e) { out.bad(e.getMessage()); return; }
        if (plains.isEmpty()) { out.note("the file holds nothing"); return; }
        out.note(plains.size() + " record(s) in the file   ·   " + svc.vaultCount() + " already here");
        if (!in.yes("import " + plains.size() + " into this cofre?", false)) { out.note("cancelado"); return; }
        List<VaultDraft> drafts = new ArrayList<>();
        for (VaultPayload.Plain p : plains)
            drafts.add(new VaultDraft(p.companyId(), VaultCrypto.encryptText(key, VaultPayload.json(p), VaultCrypto.aadItem(p.companyId()))));
        try {
            out.ok("importado " + svc.createVaultItems(drafts).size() + "   ·   v l lists them");
        } catch (RuntimeException e) {
            out.bad("nothing was imported   ·   " + e.getMessage());
        }
    }

    /**
     * A new master password: everything is opened with the old key, re-sealed with the
     * new one, and the whole set is written in one request. Either all of it moves or
     * none of it — a half-rekeyed vault would open for nobody.
     */
    private void cofreRekey() {
        out.title("COFRE · NEW MASTER PASSWORD");
        VaultMeta m = svc.vaultMeta();
        String from = in.secret("current master password");
        SecretKey oldKey;
        try { oldKey = VaultCrypto.deriveKey(from, VaultCrypto.decode(m.salt()), m.iterations()); }
        catch (RuntimeException e) { out.bad(e.getMessage()); return; }
        if (!VaultCrypto.checkOk(oldKey, m.check())) { out.bad("wrong current password   ·   nothing changes"); return; }

        String to = in.secret("new master password");
        if (to.isEmpty() || to.equals(from)) { out.bad("the new password has to be a different one"); return; }
        if (!to.equals(in.secret("again, to be sure"))) { out.bad("not the same   ·   nothing changed"); return; }

        int iters = inIterations();
        String salt = VaultCrypto.encode(VaultCrypto.randomSalt());
        SecretKey newKey = VaultCrypto.deriveKey(to, VaultCrypto.decode(salt), iters);

        List<VaultRewrap> items = new ArrayList<>();
        for (VaultItem it : svc.vaultItems(0)) {
            VaultPayload.Plain p = open(oldKey, it);
            items.add(new VaultRewrap(it.id(), VaultCrypto.encryptText(newKey, VaultPayload.json(p), VaultCrypto.aadItem(p.companyId())), it.revision()));
        }
        if (!in.yes("re-seal " + items.size() + " record(s)?", false)) { out.note("cancelado"); return; }
        VaultMeta after = svc.vaultRekey(VaultCrypto.KDF, salt, iters, VaultCrypto.checkBlob(newKey), items);
        cofreKey = newKey;
        out.ok("cofre re-selado   ·   " + after.iterations() + " interações   ·   " + items.size() + " record(s)");
        out.warn("a backup taken before this was sealed with the old salt: it still opens, with this password");
    }

    // ---- cofre helpers -----------------------------------------------------

    /** Open one record. A record that will not open is named, never shown as blank. */
    private VaultPayload.Plain open(SecretKey key, VaultItem it) {
        try { return VaultPayload.parse(VaultCrypto.decryptText(key, it.blob(), VaultCrypto.aadItem(it.companyId()))); }
        catch (Problema e) { throw new Problema("cofre #" + it.id() + ": " + e.getMessage()); }
    }

    private VaultItem cofreById(int id) {
        try { return svc.vaultItemById(id); } catch (Problema ignored) { return null; }
    }

    private String companyName(int id) {
        try { return svc.companyById(id).name(); } catch (Problema ignored) { return "company #" + id; }
    }

    /** The secret, masked: how long it is, never what it is. */
    private static String mask(String pass) {
        if (pass.isEmpty()) return "(empty)";
        int n = Math.min(pass.length(), 12);
        return "•".repeat(n) + (pass.length() > n ? " +" + (pass.length() - n) : "") + " (" + pass.length() + ")";
    }

    /**
     * The terminal's honest word about a password. It counts the character classes it
     * can see and subtracts nothing else: it knows no dictionary, no username, no
     * birthday and no leaked corpus, and says so by being modest rather than confident.
     */
    private static String strengthLabel(String pass) {
        if (pass.isEmpty()) return "empty";
        boolean upper = pass.chars().anyMatch(Character::isUpperCase);
        boolean lower = pass.chars().anyMatch(Character::isLowerCase);
        boolean digits = pass.chars().anyMatch(Character::isDigit);
        boolean other = pass.chars().anyMatch(c -> !Character.isLetterOrDigit(c));
        if (pass.length() < VaultGen.MIN_LENGTH) return "too short";
        double bits = VaultGen.bitsOf(pass.length(), upper, lower, digits, other, false);
        if (bits < 36) return "too weak";
        if (bits < 60) return "weak";
        if (bits < 80) return "ok";
        if (bits < 120) return "strong";
        return "very strong";
    }

    /** Generate, print once, and say the rules that made it. */
    private void cofreGen(String rest) {
        int length = VaultGen.DEFAULT_LENGTH;
        if (!rest.isEmpty()) {
            try { length = Parse.num(rest, "longueur"); }
            catch (Bad e) { out.bad(e.getMessage()); return; }
        }
        boolean similar = in.yes("drop il1LoO0?", false);
        try {
            String pass = VaultGen.generate(length, true, true, true, true, similar);
            out.ok("senha   " + pass);
            out.note(VaultGen.sets(true, true, true, true, similar) + "   ·   ~" + Math.round(VaultGen.bitsOf(length, true, true, true, true, similar)) + " bits");
            out.warn("a terminal keeps what it prints: this line is in your scrollback");
        } catch (Bad e) { out.bad(e.getMessage()); }
    }

    /** The company a credential belongs to. The active one is the default. */
    private int pickCompany(int def) {
        var all = svc.listCompanies();
        for (var co : all) out.note("  #" + co.id() + "  " + co.name());
        String pick = in.text("empresa", "#" + def);
        if (pick.startsWith("#")) {
            String n = "";
            for (char ch : pick.substring(1).toCharArray()) { if (Character.isDigit(ch)) n += ch; else break; }
            if (!n.isEmpty()) return Integer.parseInt(n);
        }
        for (var co : all) if (pick.equalsIgnoreCase(co.name())) return co.id();
        var act = svc.activeCompany();
        out.warn("not one of those   ·   " + act.name() + " #" + act.id());
        return act.id();
    }

    /** The iteration count, kept inside the bounds the server accepts. */
    private int inIterations() {
        int def = Math.max(VaultCrypto.ITERATIONS, svc.vaultMeta().iterations());
        int n = in.num("interações", def);
        if (n < VaultCrypto.MIN_ITERATIONS || n > VaultCrypto.MAX_ITERATIONS) {
            out.warn(n + " is outside " + VaultCrypto.MIN_ITERATIONS + ".." + VaultCrypto.MAX_ITERATIONS + "   ·   " + def + " instead");
            return def;
        }
        return n;
    }

    /** A generated password unless the user typed one. */
    private static String generated(String typed) {
        if (typed.isEmpty()) return VaultGen.generate();
        if (typed.startsWith("pass=")) return typed.substring(5);
        return typed;
    }

    private static String editedPass(String typed, String cur) {
        if (typed.isEmpty()) return cur;
        if (typed.equalsIgnoreCase("g")) return VaultGen.generate();
        if (typed.startsWith("pass=")) return typed.substring(5);
        return typed;
    }

    /** The field length rule, as one sentence. Empty means the record is fine. */
    private static String tooLong(VaultPayload.Plain p) {
        if (p.app().length() > VaultPayload.APP_MAX) return "aplicativo mais longa que " + VaultPayload.APP_MAX;
        if (p.user().length() > VaultPayload.USER_MAX) return "usuario mais longa que " + VaultPayload.USER_MAX;
        if (p.pass().length() > VaultPayload.PASS_MAX) return "senha mais longa que " + VaultPayload.PASS_MAX;
        if (p.desc().length() > VaultPayload.DESC_MAX) return "descrição mais longa que " + VaultPayload.DESC_MAX;
        return "";
    }

    /** The first integer on a line, or 0. Never throws. */
    private static int safeId(String s) {
        String n = "";
        for (char ch : s.strip().toCharArray()) {
            if (Character.isDigit(ch)) n += ch;
            else if (!n.isEmpty()) break;
        }
        if (n.isEmpty()) return 0;
        try { return Integer.parseInt(n); } catch (NumberFormatException ignored) { return 0; }
    }

    private static String cofreCols(String... v) {
        int[] w = {5, 5, 18, 14, 20};
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length; i++) b.append(i > 0 ? "  " : "").append(Str.fix(v[i], w[i]));
        return b.toString();
    }

    private String range(ponto.app.ConfigView c) {
        String a = c.from(), b = c.to();
        return a.isEmpty() ? "-" : a + " .. " + b;
    }

    private void help() {
        out.title("TECLAS");
        out.note("1 novo  2 editar [id]   3 apagar [id id.. | a | dd/mm]   4 lista [n]");
        out.note("5 soma  6 buscar [texto]   7 pdf   8 xls   9 csv   d por data");
        out.note("c config   f falta [dia | lista]   v cofre   x limpar tela   ? ajuda   0 sair");
        out.note("cofre  v c creates · v status · v l [texto] · v n novo · v e [id] · v s [id] mostra · v x [id..]");
        out.note("cofre  v g [n] genera · v b [file] backup · v i [file] import · v k master · v q lock");
        out.note("cofre  one master password, no recovery: lose it and the cofre is empty forever");
        out.note("falta  f marca hoje, f 03/11/2026 marca aquele dia, f lista lista (banco de horas)");
        out.note("por data  2026 (ano) · 10/2026 · out · out 2026 · 01/10/2026 · hoje");
        out.note("         4 2026 funciona do mesmo jeito que d 2026");
        out.note("datas  08/10 · 08/10/2026 · 2026-10-08 · 5out · hoje · ontem · -3");
        out.note("horas  07:30 · 07h30 · 0730 · 8 · agora       '-' limpa um campo");
        out.note("banco  " + svc.dbPath() + "   (PONTO_DB / --db=url)");
    }
}
