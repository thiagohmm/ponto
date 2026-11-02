package ponto.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Aggregates for the on-screen summary and for every exported report. */
public final class Report {

    public record Head(String who, String company, String db, Period p, String generated) {
        public String title() { return "Relatório de pontos"; }
        public String periodo() { return Str.d(p.from()) + " - " + Str.d(p.to()); }
    }

    public record Day(LocalDate dia, String d3, int lines, long mins) {
        public double hours() { return mins / 60.0; }
        public String dec() { return Dur.dec(mins); }
    }

    private static final Comparator<Entry> ORDER =
            Comparator.comparing(Entry::dia)
                     .thenComparing(e -> e.tin() == null ? -1 : Str.tod(e.tin()))
                     .thenComparing(Entry::id);

    private final Head head;
    private final List<Entry> rows;
    private final List<Day> days;
    private final long total;
    private final int open;
    private final long saldoMins;
    private final int faltas;
    private final String planoLabel;

    public Report(Head head, List<Entry> rows, long saldoMins, int faltas, String planoLabel) {
        this.head = head;
        this.rows = rows.stream().sorted(ORDER).toList();
        this.saldoMins = saldoMins;
        this.faltas = faltas;
        this.planoLabel = planoLabel;

        List<Day> acc = new ArrayList<>();
        long t = 0;
        int op = 0;
        for (Entry e : this.rows) {
            long m = Math.max(0, e.min());
            t += m;
            if (e.open()) op++;
            int i = acc.size() - 1;
            if (i < 0 || !acc.get(i).dia().equals(e.dia())) acc.add(new Day(e.dia(), Str.d3(e.dia()), 1, m));
            else { Day d = acc.get(i); acc.set(i, new Day(d.dia(), d.d3(), d.lines() + 1, d.mins() + m)); }
        }
        days = acc;
        total = t;
        open = op;
    }

    public Head h() { return head; }
    public List<Entry> lines() { return rows; }
    public List<Day> summary() { return days; }
    public long totalMin() { return total; }
    public int openLines() { return open; }
    public String totalLabel() { return Dur.hm(total); }
    public String totalDec() { return Dur.dec(total); }

    /** Banco de horas of the period, faltas marked in it, and the plan label (current period only). */
    public long saldoMins() { return saldoMins; }
    public String saldoLabel() { return Dur.signed(saldoMins); }
    public int faltas() { return faltas; }
    public String planoLabel() { return planoLabel; }

    /** Rows outside the requested period would be a bug; the filter lives in Repo. */
    boolean inRange(LocalDate d) { return head.p().has(d); }
}
