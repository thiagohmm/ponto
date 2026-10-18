package ponto;

import java.io.IOException;
import java.util.List;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.BuiltinFormats;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

/**
 * Genuine Excel .xls (BIFF8) via POI/HSSF: header block, styled table, a
 * TOTAL line, and a second sheet with the per-day summary.
 */
final class Xls {

    private static final String[] COLS = {"ID", "DATE", "DAY", "IN", "OUT", "DURATION", "HOURS", "JUSTIFICATION"};
    private static final String[] SCOLS = {"DATE", "DAY", "LINES", "DURATION", "HOURS"};
    private static final int[] CW = {6, 11, 5, 6, 6, 9, 8, 62};
    private static final int[] SW = {11, 5, 7, 10, 8};

    static byte[] write(Path f, Report r) {
        HSSFWorkbook wb = new HSSFWorkbook();
        try {
            St st = new St(wb);
            sheet(wb, st, "REGISTRO", COLS, CW, r, detail(r), NUM);
            sheet(wb, st, "RESUMO", SCOLS, SW, r, summary(r), SNUM);
            return save(wb, f);
        } finally {
            try { wb.close(); } catch (java.io.IOException ignored) { }
        }
    }

    private static byte[] save(HSSFWorkbook wb, Path f) {
        try {
            try (OutputStream os = Files.newOutputStream(f)) { wb.write(os); }
            return Files.readAllBytes(f);
        } catch (IOException e) { throw new Problema("xls: " + e.getMessage()); }
    }

    // ---- layout ----------------------------------------------------------

    /** Only the listed columns become real numbers; hours stay text so the separator is always ".". */
    private static void sheet(Workbook wb, St st, String name, String[] head, int[] cw,
                              Report r, String[][] body, List<Integer> num) {
        Sheet sh = wb.createSheet(name);
        for (int i = 0; i < cw.length; i++) sh.setColumnWidth(i, cw[i] * 256);

        int at = 0;
        title(sh, st, r);
        at = 6;

        Row h = sh.createRow(at);
        h.setHeightInPoints(16);
        for (int i = 0; i < head.length; i++) cell(sh, h, i, head[i], st.head);

        for (String[] row : body) { putRow(sh, ++at, row, st, num); }

        totalRow(sh, st, head, r, at + 1);
        sh.createFreezePane(0, 7);
    }

    /** TOTAL under the table: every value in its own column, nothing spilled sideways. */
    private static void totalRow(Sheet sh, St st, String[] head, Report r, int at) {
        Row row = sh.createRow(at);
        int lines = col(head, "LINES");
        for (int i = 0; i < head.length; i++) {
            if (i == 0) { cell(sh, row, i, "TOTAL", st.total); continue; }
            if (head[i].equals("LINES")) { number(sh, row, i, r.lines().size(), st.totalNum); continue; }
            if (head[i].equals("DURATION")) { cell(sh, row, i, r.totalLabel(), st.total); continue; }
            if (head[i].equals("HOURS")) { cell(sh, row, i, r.totalDec(), st.total); continue; }
            cell(sh, row, i, "", st.total);
        }
        if (r.openLines() > 0) {
            cell(sh, sh.createRow(at + 1), 0, r.openLines() + " incomplete line(s): no pair, not counted", st.meta);
        }
    }

    private static void title(Sheet sh, St st, Report r) {
        cell(sh, sh.createRow(0), 0, r.h().title(), st.big);
        cell(sh, sh.createRow(1), 0, r.h().periodo(), st.meta);
        cell(sh, sh.createRow(2), 0, "user " + dash(r.h().who()) + "   ·   database " + r.h().db(), st.meta);
        cell(sh, sh.createRow(4), 0, "lines " + r.lines().size() + "   ·   incomplete " + r.openLines(), st.meta);
        cell(sh, sh.createRow(5), 0, "generated " + r.h().generated(), st.meta);
    }

    private static String[][] detail(Report r) {
        String[][] out = new String[r.lines().size()][];
        int i = 0;
        for (Entry e : r.lines()) {
            out[i++] = new String[] { Str.s(e.id()), Str.d(e.dia()), Str.d3(e.dia()), Str.t(e.tin()), Str.t(e.tout()),
                                       e.durCol(), e.hoursCol(), e.just() };
        }
        return out;
    }

    private static String[][] summary(Report r) {
        String[][] out = new String[r.summary().size()][];
        int i = 0;
        for (Report.Day d : r.summary()) {
            out[i++] = new String[] { Str.d(d.dia()), d.d3(), Str.s(d.lines()), Dur.hm(d.mins()), Str.n(d.hours()) };
        }
        return out;
    }

    // ---- cells -----------------------------------------------------------

    /** The column of a header label, or the last column when it has none. */
    private static int col(String[] head, String label) {
        for (int i = 0; i < head.length; i++) if (head[i].equals(label)) return i;
        return head.length - 1;
    }

    private static void putRow(Sheet sh, int at, String[] vals, St st, List<Integer> num) {
        Row row = sh.createRow(at);
        for (int i = 0; i < vals.length; i++) {
            if (num.contains(i) && !vals[i].isEmpty()) number(sh, row, i, Double.parseDouble(vals[i]), st.num);
            else cell(sh, row, i, vals[i], st.cell);
        }
    }

    private static void cell(Sheet sh, Row row, int col, String s, CellStyle st) {
        Cell c = row.createCell(col);
        c.setCellStyle(st);
        c.setCellValue(s);
    }

    private static void number(Sheet sh, Row row, int col, double v, CellStyle st) {
        Cell c = row.createCell(col);
        c.setCellStyle(st);
        c.setCellValue(v);
    }

    private static final List<Integer> NUM = java.util.List.of(0);
    private static final List<Integer> SNUM = java.util.List.of(2);

    private static String dash(String s) { return s == null || s.isEmpty() ? "-" : s; }

    /** Five styles total (the HSSF record budget is generous, but small is better). */
    private static final class St {
        final CellStyle big, meta, head, cell, total, totalNum, num;

        St(Workbook wb) {
            big   = mk(wb, 16, true,  null,                  false);
            meta  = mk(wb, 9,  false, null,                  false);
            head  = mk(wb, 10, true,  IndexedColors.GREY_25_PERCENT, true);
            cell  = mk(wb, 10, false, null,                 true);
            total = mk(wb, 11, true,  IndexedColors.GREY_50_PERCENT, true);
            num   = mk(wb, 10, false, null,                 true);
            num.setDataFormat((short) BuiltinFormats.getBuiltinFormat("0"));
            totalNum = mkB(wb);
        }

        /** Bold version of the integer style (for the TOTAL count). */
        private CellStyle mkB(Workbook wb) {
            Font f = wb.createFont();
            f.setFontHeightInPoints((short) 11);
            f.setBold(true);
            CellStyle s = wb.createCellStyle();
            s.setFont(f);
            s.setDataFormat((short) BuiltinFormats.getBuiltinFormat("0"));
            s.setBorderBottom(BorderStyle.THIN);
            s.setBorderTop(BorderStyle.THIN);
            s.setBorderLeft(BorderStyle.THIN);
            s.setBorderRight(BorderStyle.THIN);
            return s;
        }

        private static CellStyle mk(Workbook wb, int pt, boolean bold, IndexedColors fill, boolean border) {
            CellStyle s = wb.createCellStyle();
            Font f = wb.createFont();
            f.setFontHeightInPoints((short) pt);
            f.setBold(bold);
            s.setFont(f);
            if (fill != null) {
                s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                s.setFillForegroundColor(fill.getIndex());
            }
            if (border) {
                s.setBorderBottom(BorderStyle.THIN);
                s.setBorderTop(BorderStyle.THIN);
                s.setBorderLeft(BorderStyle.THIN);
                s.setBorderRight(BorderStyle.THIN);
            }
            return s;
        }
    }
}
