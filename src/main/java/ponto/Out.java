package ponto;

import java.io.PrintStream;

/** Terminal rendering: colour only when attached to a real TTY. */
final class Out {

    static final boolean TTY = System.console() != null;
    private static final boolean COLOR =
            TTY && System.getenv("NO_COLOR") == null && !eq(System.getenv("TERM"), "dumb");

    private static boolean eq(String a, String b) { return a != null && a.equals(b); }

    private static final String R = "\u001B[0m";
    private static final String DIM = "\u001B[2m";
    private static final String BOLD = "\u001B[1m";
    private static final String RED = "\u001B[31m";
    private static final String GRN = "\u001B[32m";
    private static final String YEL = "\u001B[33m";
    private static final String CYA = "\u001B[36m";

    private static final String RULE = "-".repeat(56);

    private final PrintStream ps = System.out;
    /** The screen is wiped once, for the first menu only. */
    private boolean painted = false;

    static String dim(String s) { return COLOR ? DIM + s + R : s; }
    static String bold(String s) { return COLOR ? BOLD + s + R : s; }
    static String grn(String s) { return COLOR ? GRN + s + R : s; }
    static String yel(String s) { return COLOR ? YEL + s + R : s; }
    static String red(String s) { return COLOR ? RED + s + R : s; }

    void rule() { ps.println(dim(RULE)); }
    void blank() { ps.println(); }

    /**
     * The menu block. It clears the screen only on the first paint: the menu comes
     * back straight after every key, with no pause in between, so clearing on every
     * repaint erased LIST / SUM / FIND before there was any chance to read them.
     * Later repaints just leave a blank line, so what the key printed stays above
     * the menu and in the scrollback.
     */
    void home(String name, int records, long mins, String dbPath, String openNote) {
        if (TTY && !painted) ps.print("\u001B[2J\u001B[H"); else ps.println();
        painted = true;
        rule();
        ps.println("  " + bold("P O N T O"));
        ps.println("  " + dim(name.isEmpty() ? "no user" : name)
                   + " ".repeat(Math.max(1, 22 - name.length()))
                   + dim(records + " records"));
        ps.println("  " + dim("this month " + Dur.hm(mins) + "   " + openNote));
        ps.println("  " + dim(dbPath));
        rule();
        // four keys per row, never wider than the rule above, so nothing can be
        // clipped or wrapped away on a narrow terminal
        ps.println("  " + bold("1") + " new     " + bold("2") + " edit    " + bold("3") + " delete  " + bold("4") + " list");
        ps.println("  " + bold("5") + " sum     " + bold("6") + " find    " + bold("7") + " pdf     " + bold("8") + " xls");
        ps.println("  " + bold("9") + " csv     " + bold("d") + " date    " + bold("c") + " config  " + bold("x") + " clear");
        ps.println("  " + bold("?") + " help    " + bold("0") + " quit");
        rule();
    }

    /** "> " waiting for a menu key. */
    void key() { ps.print(COLOR ? CYA + "> " + R : "> "); }

    /** "label [default] > " */
    void ask(String label, String def) {
        String tail = def == null || def.isEmpty() ? "" : dim("[" + def + "] ");
        ps.print("  " + label + " " + tail + (COLOR ? CYA + "> " + R : "> "));
    }

    /** "label > " */
    void ask(String label) { ask(label, null); }

    /** Wipe the screen on purpose (key x); run() repaints the menu right after. */
    void clear() { if (TTY) { ps.print("\u001B[2J\u001B[H"); painted = true; } }

    /** Where a value lands after Enter. */
    void echo(String s) { ps.println(COLOR ? GRN + s + R : s); }
    void ok(String s) { ps.println(COLOR ? GRN + "ok  " + s + R : "ok  " + s); }
    void warn(String s) { ps.println(COLOR ? YEL + "!   " + s + R : "!   " + s); }
    void bad(String s) { ps.println(COLOR ? RED + "x   " + s + R : "x   " + s); }
    void note(String s) { ps.println(dim("    " + s)); }

    void title(String s) { ps.println(); ps.println("  " + bold(s)); }

    void nl() { ps.println(); }

    /** Two-state message. */
    void done(boolean changed, String extra) { ok((changed ? "saved " : "kept   ") + extra); }
}
