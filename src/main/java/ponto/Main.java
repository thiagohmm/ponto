package ponto;

/**
 * Entry point.
 *
 *   ponto                 -> the menu (that's the normal way to use it)
 *   ponto --serve         -> the same database, in the browser
 *   ponto --db=path     -> another database file
 *   ponto --help        -> this text
 *   ponto --version
 *
 * The interactive UI is deliberately minimal: one key per action, the current
 * value is always the default, and '-' clears a field.
 */
public final class Main {

    private static final String HELP = """
        ponto %s - electronic timesheet (SQLite): terminal or web

        usage
          ponto                     open the menu
          ponto --serve             same database, in the browser
          ponto --serve --port=8090
          ponto --db=<file>       use another database
          ponto --version | --help

        the menu
          1 new       day, in, out, justification  (warns on a weekend / day off)
          2 edit      any day / hour / justification of any record
          3 delete    a whole day or ids ("3", "3 a", "3 01/10", "3 5 6 7")
          4 list      newest records, paging, total at the bottom
          d date      every record of a day, month or year ("d 2026", "d oct")
          5 sum       totals per day for a period
          6 find      search the justifications ("6 sick")
          7 pdf       report as PDF
          8 xls       report as real Excel .xls
          9 csv       report as CSV (BOM + ';')
          c config    user name, your own days off, database path, vacuum
          x clear     wipe the screen
          0 quit

        input
          dates    08/10 · 08/10/2026 · 2026-10-08 · 5oct · today · y · -3
          by date  2026 (whole year) · 10/2026 · oct · oct 2026 · 01/10/2026
          times    07:30 · 07h30 · 0730 · 8 · now
          Enter    keeps the value shown in brackets
          -        clears the field

        days off
          Brazil and São Paulo (state and city), from jollyday, no internet needed
          a line of yellow on 1 and 2, never a wall: work on those days is still recorded
          your own days off: c config, "27/10/2026 31/12" (space, comma or ;)

        environment
          PONTO_DB          database path (default ~/.local/share/ponto/ponto.db)
          PONTO_JAVA        JDK 25+ used by the launcher
          PONTO_CSV_SEP     ',' if you prefer commas over semicolons
          PONTO_PORT        listen port for --serve (default 8090)
        """.formatted(Ver.N);

    public static void main(String[] args) {
        if (flag(args, "-h", "--help")) { System.out.println(HELP); return; }
        if (flag(args, "-v", "--version")) { System.out.println(Ver.NAME + " " + Ver.N); return; }
        String db = value(args, "--db=");
        try {
            if (flag(args, "--serve")) {
                Db d = Db.open(db);
                new Web(d, port(args)).start();
                return;
            }
            try (Db d = Db.open(db)) { new App(d).run(); }
            System.out.flush();
        } catch (Problema e) {
            System.out.flush();
            System.err.println("x   " + e.getMessage());
            System.exit(1);
        } catch (Eof ignored) {
            System.out.flush();
            System.exit(0);
        }
    }

    private static int port(String[] args) {
        String raw = value(args, "--port=");
        if (raw == null || raw.isBlank()) raw = System.getenv("PONTO_PORT");
        if (raw == null || raw.isBlank()) return 8090;
        try {
            int p = Integer.parseInt(raw);
            if (p < 1 || p > 65535) throw new NumberFormatException();
            return p;
        } catch (NumberFormatException e) {
            throw new Problema("port: '" + raw + "'");
        }
    }

    private static boolean flag(String[] args, String... names) {
        for (String a : args) for (String n : names) if (a.equals(n)) return true;
        return false;
    }

    private static String value(String[] args, String prefix) {
        for (String a : args) if (a.startsWith(prefix)) return a.substring(prefix.length());
        if (prefix.equals("--db=")) {
            for (String a : args) if (!a.startsWith("-")) return a;
        }
        return null;
    }
}
