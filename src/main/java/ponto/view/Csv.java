package ponto.view;

import ponto.model.Entry;
import ponto.model.Report;
import ponto.model.Str;
import ponto.Problema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CSV export. BOM + ";" by default so Excel (pt-BR) opens it directly;
 * override the separator with $PONTO_CSV_SEP ("," for tooling).
 */
public final class Csv {

    public static final char SEP = sep();

    private static char sep() {
        String s = System.getenv("PONTO_CSV_SEP");
        if (s == null || s.isEmpty()) return ';';
        char c = s.charAt(0);
        return (c == ',' || c == ';' || c == '\t') ? c : ';';
    }

    private static final String[] HEAD = {"ID", "DIA", "DIA_3L", "IN", "OUT", "DURACAO", "HORAS", "JUSTIFICACAO"};

    /** Renders and writes the report; returns the file content. */
    public static byte[] write(Path f, Report r) {
        StringBuilder b = new StringBuilder("\uFEFF");
        b.append(line(HEAD));
        for (Entry e : r.lines()) b.append(line(row(e)));
        b.append(line(total(r)));
        b.append('\n');
        b.append(line(kv("User", r.h().who())));
        b.append(line(kv("Period", r.h().periodo())));
        b.append(line(kv("Days", "" + r.summary().size())));
        b.append(line(kv("Total", r.totalLabel() + " (" + Str.n(r.totalMin() / 60.0) + " h)")));
        b.append(line(kv("Incomplete", "" + r.openLines())));
        b.append(line(kv("Generated", r.h().generated())));
        b.append(line(kv("Database", r.h().db())));

        byte[] out = b.toString().getBytes(StandardCharsets.UTF_8);
        try { Files.write(f, out); }
        catch (IOException e) { throw new Problema("csv: " + e.getMessage()); }
        return out;
    }

    private static String[] row(Entry e) {
        return new String[]{"" + e.id(), Str.d(e.dia()), Str.d3(e.dia()),
                Str.t(e.tin()), Str.t(e.tout()), Cols.dur(e),
                Cols.hours(e), e.just()};
    }

    private static String[] total(Report r) {
        String[] z = new String[HEAD.length];
        for (int i = 0; i < z.length; i++) z[i] = "";
        z[0] = "TOTAL";
        z[5] = r.totalLabel();
        z[6] = Str.n(r.totalMin() / 60.0);
        z[7] = "" + r.lines().size() + " lines";
        return z;
    }

    private static String[] kv(String k, String v) {
        String[] z = new String[HEAD.length];
        for (int i = 0; i < z.length; i++) z[i] = "";
        z[0] = k; z[1] = v;
        return z;
    }

    private static String line(String[] cells) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) b.append(SEP);
            b.append(esc(cells[i]));
        }
        return b.append('\n').toString();
    }

    private static boolean needsQuote(String s) {
        for (char c : s.toCharArray()) if (c == SEP || c == '"' || c == '\n' || c == '\r') return true;
        return false;
    }

    private static String esc(String s) {
        String v = s.replace("\"", "\"\"");
        return needsQuote(v) ? "\"" + v + "\"" : v;
    }
}
