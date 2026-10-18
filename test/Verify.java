package ponto;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

/**
 * Prints a stable digest of the exported PDF / XLS / CSV so test/smoke.sh can diff
 * it against test/expected.txt. The generation timestamp and the paths of the
 * database and of the test directory are masked out, everything else must match.
 *
 *   java -cp target/classes:$(cat target/cp.txt) ponto.Verify <pdf> <xls> <csv> <db>
 */
public final class Verify {

    private static String HIDE = "";    // prefix to mask (the test directory)

    public static void main(String[] args) throws Exception {
        if (args.length < 4) { System.out.println("x   need <pdf> <xls> <csv> <db>"); System.exit(2); }
        HIDE = Path.of(args[3]).getParent().toString();
        System.out.println("== PDF ==");
        System.out.println(mask(pdf(args[0])));
        System.out.println("== XLS ==");
        System.out.print(mask(xls(args[1])));
        System.out.println("== CSV ==");
        System.out.print(mask(csv(args[2])));
    }

    private static String pdf(String path) throws Exception {
        var doc = Loader.loadPDF(new File(path));
        int pages = doc.getNumberOfPages();
        PDFTextStripper strip = new PDFTextStripper();
        String text = strip.getText(doc);
        doc.close();
        return "pages=" + pages + "\n" + text.strip();
    }

    private static String xls(String path) throws Exception {
        byte[] b = Files.readAllBytes(Path.of(path));
        // Excel's own compound-document magic; HSSF writes BIFF8 inside it.
        boolean ole = b[0] == -48 && b[1] == -49 && b[2] == 17 && b[3] == -32;
        StringBuilder s = new StringBuilder("magic=" + (ole ? "xls (OLE + BIFF8)" : "NOT XLS " + b[0]) + "\n");
        var wb = new HSSFWorkbook(new ByteArrayInputStream(b));
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            Sheet sh = wb.getSheetAt(i);
            s.append("--- ").append(wb.getSheetName(i)).append(" ---\n");
            for (int r = sh.getFirstRowNum(); r <= sh.getLastRowNum(); r++) {
                Row row = sh.getRow(r);
                if (row == null) continue;
                s.append("  ").append(rowText(row)).append("\n");
            }
        }
        wb.close();
        return s.toString();
    }

    private static String csv(String path) throws Exception {
        String body = Files.readAllLines(Path.of(path)).stream()
                         .map(l -> l.replace("\uFEFF", ""))
                         .filter(l -> !l.isBlank())
                         .filter(l -> !l.startsWith("UTF-8"))
                         .collect(java.util.stream.Collectors.joining("\n"));
        return body + "\n";
    }

    /**
     * A golden file must hold on any machine: the generation clock is dropped, the
     * test directory and the OS user name are replaced by fixed words.
     */
    private static String mask(String s) {
        String user = System.getProperty("user.name");
        StringBuilder out = new StringBuilder();
        for (String ln : s.split("\n")) {
            if (ln.toLowerCase().contains("generated") || ln.toLowerCase().contains("generado")) continue;
            int i = HIDE.isEmpty() ? -1 : ln.indexOf(HIDE);
            if (i >= 0) ln = ln.substring(0, i) + "TESTDIR/…";
            if (!user.isEmpty()) ln = ln.replace(user, "USER");
            out.append(ln).append("\n");
        }
        return out.toString();
    }

    /** One sheet row as "a | b | c", numbers with the shortest spelling. */
    private static String rowText(Row row) {
        StringBuilder ln = new StringBuilder();
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell == null) continue;
            if (cell.getCellType() == CellType.NUMERIC) ln.append(num(cell.getNumericCellValue()));
            else if (cell.getCellType() != CellType.BLANK) ln.append(cell.toString().trim());
            ln.append(" | ");
        }
        return ln.toString().strip();
    }

    /** 1.5 -> "1.5", 1.0 -> "1" (the point of forcing the decimal style). */
    private static String num(double v) {
        if (v == (long) v) return "" + (long) v;
        String s = "" + v;
        return s.length() > 6 ? s.substring(0, 6) : s;
    }
}
