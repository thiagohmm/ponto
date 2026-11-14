package ponto.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Weekends and the days off in Brazil, seen from São Paulo.
 *
 * The calendar is written here, in plain Java: national days plus the state of
 * São Paulo and the city of São Paulo. The mobile holidays are the ones that
 * move with Easter (Carnival, Ash Wednesday, Good Friday, Corpus Christi), so
 * the only calculation is the Easter date.
 *
 * That list came from jollyday's `holidays/Holidays_br.xml` (BR + `sp` + `csp`)
 * and was compared against the city's own calendar (Prefecture of São Paulo,
 * Decree 64.862/2025 for 2026); three days are missing from it, two of them
 * "pontos facultativos", the optional days off, where a punch is normal work
 * and not a mistake. They are added below, each one saying why.
 *
 * Two more come from the same decree: Carnival Tuesday and Ash Wednesday are
 * listed as facultative there, while the XML called them PUBLIC, so they are
 * marked optional here. The Corpus Christi date is left exactly as the XML and
 * Nager give it (04/06/2026) -- the city decree agrees, so it is not a bug.
 *
 * Having the dates in Java (instead of a calendar library that loads XML) means
 * one less dependency, no parser at startup and no way for a broken resource to
 * change a working day. A failure here can only ever hide a warning; it must
 * never stop a punch.
 */
public final class Holidays {

    /** One year, built once and kept: date -> the day off on it. */
    private static final ConcurrentHashMap<Integer, Map<LocalDate, Off>> YEARS = new ConcurrentHashMap<>();

    /** Days the user calls off too (`c config`, key `off`). */
    private final java.util.function.Supplier<List<LocalDate>> extras;

    public Holidays(java.util.function.Supplier<List<LocalDate>> extras) { this.extras = extras; }

    /** One day off: what it is called, and whether the whole day is off. */
    record Off(String label, boolean optional) {
        String text() { return (optional ? "facultativo: " : "feriado: ") + label; }
    }

    /** A fixed date the calendar has to be patched with: the date, the name, the tier. */
    private record Fix(int month, int day, String label, boolean optional) {
        LocalDate in(int year) { return LocalDate.of(year, month, day); }
    }

    /** Days the national/state/city list does not have. */
    private static final List<Fix> MISSING = List.of(
        new Fix(12, 26, "Confraternização Universal", false),  // national day off, not in the list
        new Fix(12, 24, "Véspera de Natal", true),             // optional day off, city decree
        new Fix(12, 31, "Véspera de Ano Novo", true));         // optional day off, city decree

    /** The Monday of Carnival: the list knows the Tuesday only. */
    private static final long CARNIVAL_MONDAY_BEFORE_EASTER = 48;

    /** "" on a working day; otherwise what to say after the date. */
    public String off(LocalDate d) {
        Off o = year(d.getYear()).get(d);
        if (o == null) return weekend(d) ? "fim de semana" : "";
        return o.text() + (weekend(d) ? "   ·   fim de semana" : "");
    }

    static boolean weekend(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY;
    }

    /** The next `n` days off from today on, for `c config`. */
    public List<String> next(int n) {
        List<String> r = new ArrayList<>();
        LocalDate today = ponto.model.Time.today();
        for (int step = 0; step < 2 && r.size() < n; step++) {
            for (var e : year(today.getYear() + step).entrySet()) {
                if (!e.getKey().isBefore(today)) r.add(Str.d(e.getKey()) + "   " + e.getValue().text());
            }
        }
        return r.stream().limit(n).toList();
    }

    private Map<LocalDate, Off> year(int year) {
        TreeMap<LocalDate, Off> days = new TreeMap<>(of(year));
        for (LocalDate d : extras.get()) if (d.getYear() == year) days.put(d, new Off("sua folga", false));
        return days;
    }

    private static Map<LocalDate, Off> of(int year) {
        Map<LocalDate, Off> hit = YEARS.get(year);
        if (hit != null) return hit;
        TreeMap<LocalDate, Off> t = new TreeMap<>();
        LocalDate easter = easter(year);

        // national
        put(t, year, 1, 1, "Confraternização Universal", false);
        put(t, year, 4, 21, "Tiradentes", false);
        put(t, year, 5, 1, "Dia do Trabalho", false);
        put(t, year, 9, 7, "Independência", false);
        if (year >= 1980) put(t, year, 10, 12, "Nossa Senhora Aparecida", false);
        put(t, year, 11, 2, "Finados", false);
        put(t, year, 11, 15, "Proclamação da República", false);
        if (year >= 2024) put(t, year, 11, 20, "Dia da Consciência Negra", false);
        put(t, year, 12, 25, "Natal", false);

        // the state of São Paulo, and the city itself
        put(t, year, 7, 9, "Revolução de 1932 (São Paulo)", false);
        put(t, year, 11, 20, "Dia da Consciência Negra (São Paulo)", false);
        put(t, year, 1, 25, "Fundação de São Paulo (cidade)", false);

        // Easter and the days that walk with it
        put(t, easter, "Domingo de Páscoa", false);
        put(t, easter.minusDays(CARNIVAL_MONDAY_BEFORE_EASTER), "Segunda-feira de Carnaval", true);
        put(t, easter.minusDays(CARNIVAL_MONDAY_BEFORE_EASTER - 1), "Terça-feira de Carnaval", true);
        put(t, easter.minusDays(CARNIVAL_MONDAY_BEFORE_EASTER - 2), "Quarta-feira de Cinzas (até 12h)", true);
        put(t, easter.minusDays(2), "Sexta-feira Santa", false);
        put(t, easter.plusDays(60), "Corpus Christi", false);

        for (Fix f : MISSING) t.putIfAbsent(f.in(year), new Off(f.label(), f.optional()));
        Map<LocalDate, Off> made = Collections.unmodifiableSortedMap(t);
        YEARS.put(year, made);
        return made;
    }

    private static void put(Map<LocalDate, Off> t, int year, int month, int day, String label, boolean optional) {
        put(t, LocalDate.of(year, month, day), label, optional);
    }

    /** First name wins: the national label beats the state one on the same date. */
    private static void put(Map<LocalDate, Off> t, LocalDate d, String label, boolean optional) {
        t.putIfAbsent(d, new Off(label, optional));
    }

    /**
     * Easter Sunday in the Gregorian calendar (Anonymous computus). Easter is
     * the anchor of every mobile day off in Brazil.
     */
    static LocalDate easter(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = (h + l - 7 * m + 114) % 31 + 1;
        return LocalDate.of(year, month, day);
    }
}
