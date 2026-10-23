package ponto;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;
import org.apache.pdfbox.util.Matrix;

/**
 * PDF report (PDFBox 3). A4 portrait, running header, a paginated detail
 * table whose justification column word-wraps, incomplete lines shaded, and a
 * final per-day summary page.
 */
final class Pdf {

    private static final float M = 42;                                     // margin
    private static final float W = PDRectangle.A4.getWidth() - 2 * M;  // usable width
    private static final float TOP = 800, BOT = 74, LEAD = 10;

    // column offsets from the left margin, and the width of the last one
    private static final float[] X = {0, 34, 94, 120, 154, 188, 232, 276};
    private static final float JW = W - 276;
    private static final String[] HEAD = {"ID", "DATE", "DAY", "IN", "OUT", "DUR", "HOURS", "JUSTIFICATION"};

    private static final PDFont REG = new PDType1Font(FontName.HELVETICA);
    private static final PDFont BOLD = new PDType1Font(FontName.HELVETICA_BOLD);

    static long write(Path f, Report r) {
        PDDocument doc = new PDDocument();
        try {
            doc.setDocumentInformation(info(r));
            Page pg = new Page(doc);
            detail(pg, r);
            summary(pg, r);
            pg.close();
            try (OutputStream os = Files.newOutputStream(f)) { doc.save(os); }
            return Files.size(f);
        } catch (IOException e) {
            throw new Problema("pdf: " + e.getMessage());
        } finally {
            try { doc.close(); } catch (IOException ignored) { }
        }
    }

    private static PDDocumentInformation info(Report r) {
        PDDocumentInformation i = new PDDocumentInformation();
        i.setTitle(r.h().title());
        i.setAuthor(r.h().who());
        i.setSubject("ponto " + r.h().periodo());
        i.setCreator("ponto");
        i.setProducer("ponto " + Ver.N);
        return i;
    }

    // ---- pages -----------------------------------------------------------

    private static void detail(Page pg, Report r) throws IOException {
        pg.text(0, r.h().title().toUpperCase(), BOLD, 16);
        pg.gap(22);
        pg.text(0, r.h().title(), REG, 12);
        pg.gap(14);
        pg.text(0, "period    " + r.h().periodo(), REG, 10);
        pg.gap(13);
        pg.text(0, "user      " + dash(r.h().who()), REG, 10);
        pg.gap(13);
        pg.text(0, "database  " + r.h().db(), REG, 8);
        pg.gap(11);
        pg.text(0, "lines     " + r.lines().size() + "   incomplete  " + r.openLines(), REG, 10);
        pg.gap(13);
        pg.text(0, "total     " + r.totalLabel() + "  (" + r.totalDec() + " h)", BOLD, 10);
        pg.gap(13);
        pg.text(0, "generated " + r.h().generated(), REG, 8);
        pg.gap(16);
        pg.rule();
        pg.gap(6);
        pg.head();
        for (Entry e : r.lines()) {
            if (!pg.room(rowH(e))) { pg.page(); pg.head(); }
            pg.entry(e);
        }
        if (!pg.room(50)) pg.page();
        pg.gap(12);
        pg.rule();
        pg.gap(6);
        float b = pg.y;
        pg.text(X[0], "TOTAL", BOLD, 9, b);
        pg.text(X[5], r.totalLabel(), BOLD, 9, b);
        pg.text(X[6], r.totalDec(), BOLD, 9, b);
        pg.text(X[7], r.lines().size() + " lines"
              + (r.openLines() > 0 ? "  |  " + r.openLines() + " incomplete" : ""), REG, 9, b);
    }

    private static void summary(Page pg, Report r) throws IOException {
        pg.page();
        float[] sx = colX();
        pg.text(0, "SUMMARY BY DAY", BOLD, 13);
        pg.gap(20);
        pg.text(0, "Relatório de pontos  |  " + r.h().periodo(), REG, 10);
        pg.gap(13);
        pg.text(0, "user     " + (r.h().who().isEmpty() ? "-" : r.h().who()), REG, 10);
        pg.gap(13);
        pg.text(0, "generated " + r.h().generated(), REG, 8);
        pg.gap(16);
        pg.rule();
        pg.gap(6);
        String[] hd = {"DATE", "DAY", "LINES", "DUR"};
        for (int i = 0; i < hd.length; i++) pg.text(sx[i], hd[i], BOLD, 8);
        pg.right("HOURS", BOLD, 8, pg.y + 4);
        pg.gap(17);
        pg.rule();
        pg.gap(6);
        for (Report.Day d : r.summary()) {
            float b = pg.y;
            pg.text(sx[0], Str.d(d.dia()), REG, 9, b);
            pg.text(sx[1], d.d3(), REG, 9, b);
            pg.text(sx[2], "" + d.lines(), REG, 9, b);
            pg.text(sx[3], Dur.hm(d.mins()), REG, 9, b);
            pg.right(fmt(d.mins() / 60.0), REG, 9, b);
            pg.gap(15);
        }
        pg.rule();
        pg.gap(6);
        float b = pg.y;
        pg.text(sx[0], "TOTAL", BOLD, 9, b);
        pg.text(sx[2], "" + r.lines().size(), BOLD, 9, b);
        pg.text(sx[3], r.totalLabel(), BOLD, 9, b);
        pg.right(r.totalDec(), BOLD, 9, b);
    }

    // ---- layout helper ---------------------------------------------------

    private static final class Page implements AutoCloseable {
        private final PDDocument doc;
        private PDPageContentStream cs;
        private float y = TOP;
        private int no = 0;

        Page(PDDocument doc) throws IOException { this.doc = doc; page(); }

        void page() throws IOException {
            close0();
            PDPage p = new PDPage(PDRectangle.A4);
            doc.addPage(p);
            cs = new PDPageContentStream(doc, p);
            y = TOP;
            no++;
            foot();
        }

        private void foot() throws IOException {
            float keep = y;
            y = 34;
            text(0, "ponto " + Ver.N + "  |  " + System.getProperty("user.name", ""), REG, 7);
            text(X[7], "page " + no, REG, 7);
            y = keep;
        }

        boolean room(float need) { return y - need > BOT; }

        void gap(float v) { y -= v; }

        void text(float x, String s, PDFont f, float size) throws IOException { text(x, s, f, size, y); }

        void text(float x, String s, PDFont f, float size, float baseline) throws IOException {
            if (s.isEmpty()) return;
            Matrix m = new Matrix();
            m.translate(M + x, baseline);
            cs.beginText();
            cs.setFont(f, size);
            cs.setTextMatrix(m);
            cs.showText(s);
            cs.endText();
        }

        void right(String s, PDFont f, float size, float baseline) throws IOException {
            if (s.isEmpty()) return;
            text(W - width(s, f, size), s, f, size, baseline);
        }

        void head() throws IOException {
            cs.setNonStrokingColor(0.88f);
            cs.addRect(M, y - 3, W, 13);
            cs.fill();
            cs.setNonStrokingColor(0f);
            float b = y + 4;
            for (int i = 0; i < HEAD.length; i++) text(X[i], HEAD[i], BOLD, 8, b);
            y -= 17;
        }

        void rule() throws IOException {
            cs.setLineWidth(0.4f);
            cs.moveTo(M, y);
            cs.lineTo(M + W, y);
            cs.stroke();
        }

        void entry(Entry e) throws IOException {
            List<String> js = wrap(e.just(), JW);
            float hh = LEAD * Math.max(1, js.size()) + 5;
            float b = y;
            if (!e.closed()) {
                cs.setNonStrokingColor(0.94f);
                cs.addRect(M, b - hh + 3, W, hh);
                cs.fill();
                cs.setNonStrokingColor(0f);
            }
            text(X[0], "" + e.id(), BOLD, 8, b);
            String[] v = {Str.d(e.dia()), Str.d3(e.dia()), Str.t(e.tin()), Str.t(e.tout()),
                         e.durCol(), e.hoursCol()};
            for (int i = 1; i <= 6; i++) text(X[i], v[i - 1], REG, 8, b);
            for (int i = 0; i < js.size(); i++) text(X[7], js.get(i), REG, 8, b - i * LEAD);
            y = b - hh;
        }

        private void close0() throws IOException { if (cs != null) { cs.close(); cs = null; } }

        @Override public void close() throws IOException { close0(); }
    }

    // ---- measures --------------------------------------------------------

    /** The summary page columns: wide enough for the widest cell of each. */
    private static float[] colX() {
        String[] hd = {"DATE", "DAY", "LINES", "DUR"};
        String[] sm = {"15/05/2026", "MON", "88", "88:88"};
        float[] sx = new float[hd.length];
        float acc = 0;
        for (int i = 0; i < hd.length; i++) {
            sx[i] = acc;
            acc += Math.max(width(hd[i], BOLD, 8), width(sm[i], REG, 9)) + 14;
        }
        return sx;
    }

    private static float rowH(Entry e) { return LEAD * Math.max(1, wrap(e.just(), JW).size()) + 5; }

    private static String fmt(double v) { return Str.n(v); }

    private static String dash(String s) { return s == null || s.isEmpty() ? "-" : s; }

    private static float width(String s, PDFont f, float size) {
        try { return f.getStringWidth(s) / 1000 * size; }
        catch (IOException ignored) { return s.length() * size * 0.5f; }
    }

    /** Word wrap that never drops text (hard cut only for over-long words). */
    static List<String> wrap(String s, float max) {
        List<String> out = new ArrayList<>();
        String rest = s.strip();
        while (!rest.isEmpty()) {
            int cut = rest.length();
            while (cut > 1 && width(rest.substring(0, cut), REG, 8) > max) {
                int sp = rest.lastIndexOf(' ', cut - 1);
                cut = sp > 0 ? sp : cut - 1;
            }
            out.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        return out;
    }
}
