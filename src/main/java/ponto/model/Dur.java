package ponto.model;

/** Duration formatting and arithmetic. */
public final class Dur {

    /** minutes -> "h:mm" ("0:45", "62:40"). */
    public static String hm(long minutes) {
        if (minutes < 0) return "—";
        return (minutes / 60) + ":" + (minutes % 60 < 10 ? "0" + (minutes % 60) : "" + (minutes % 60));
    }

    /** minutes -> decimal hours, 2 decimals ("8.75"). */
    public static String dec(long minutes) {
        if (minutes < 0) return "";
        return Str.n(minutes / 60.0);
    }

    /** Parse "8.75" or "8,75" (decimal hours) into minutes. */
    public static int fromDec(String s) {
        double v = Double.parseDouble(s.trim().replace(',', '.'));
        return (int) Math.round(v * 60.0);
    }
}
