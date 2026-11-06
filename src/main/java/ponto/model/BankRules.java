package ponto.model;

/** Pure daily balance policy, shared by all reports and clients. */
public final class BankRules {
    public static final int TOLERANCE_MINS = 5;
    private BankRules() { }

    public static long day(long worked, long daily, boolean falta, boolean normal,
                           boolean off, boolean hasPunch) {
        if (falta) return worked - daily;
        if (!normal && off) return worked;
        if (!hasPunch) return 0;
        long raw = worked - daily;
        return Math.abs(raw) <= TOLERANCE_MINS ? 0 : raw;
    }
}
