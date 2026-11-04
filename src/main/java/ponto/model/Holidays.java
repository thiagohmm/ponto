package ponto.model;

import de.focus_shift.jollyday.core.Holiday;
import de.focus_shift.jollyday.core.HolidayManager;
import de.focus_shift.jollyday.core.HolidayType;
import de.focus_shift.jollyday.core.ManagerParameters;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Weekends and the days off in Brazil, seen from São Paulo.
 *
 * jollyday does the calendar: `holidays/Holidays_br.xml` is the national list,
 * and the ISO 3166-2 codes `sp` (state) and `csp` (city) add the local days --
 * 09/07 and 20/11 for the state, 25/01 for the city. That XML was compared here
 * against the city's own calendar (Prefecture of São Paulo, Decree 64.862/2025
 * for 2026) and three days are missing from it, two of them "pontos
 * facultativos", the optional days off, where a punch is normal work and not
 * a mistake. They are added below, each one saying why.
 *
 * Two more come from the same decree: Carnival Tuesday and Ash Wednesday are
 * listed as facultative there, while jollyday calls them PUBLIC, so they are
 * marked optional here. The Corpus Christi date is left exactly as jollyday and
 * Nager give it (04/06/2026) -- the city decree agrees, so it is not a bug.
 *
 * Nothing here touches the network, and a failure inside jollyday can only ever
 * hide a warning; it must never stop a punch.
 */
public final class Holidays {

    private static final String BR = "BR";
    /** ISO 3166-2: the state of São Paulo, and the city itself. */
    private static final List<String> SP = List.of("sp", "csp");

    /** One year, built once and kept: date -> the day off on it. */
    private static final ConcurrentHashMap<Integer, Map<LocalDate, Off>> YEARS = new ConcurrentHashMap<>();

    /** Days the user calls off too (`c config`, key `off`). */
    private static volatile List<LocalDate> EXTRA = List.of();

    /** One day off: what it is called, and whether the whole day is off. */
    record Off(String label, boolean optional) {
        String text() { return (optional ? "facultativo: " : "feriado: ") + label; }
    }

    /** A fixed date the XML has to be patched with: the date, the name, the tier. */
    private record Fix(int month, int day, String label, boolean optional) {
        LocalDate in(int year) { return LocalDate.of(year, month, day); }
    }

    /** Days the BR XML does not have. */
    private static final List<Fix> MISSING = List.of(
        new Fix(12, 26, "Confraternização Universal", false),  // national day off, not in the XML
        new Fix(12, 24, "Véspera de Natal", true),             // optional day off, city decree
        new Fix(12, 31, "Véspera de Ano Novo", true));         // optional day off, city decree

    /** jollyday says PUBLIC; the city decree lists these as facultative. */
    private static final List<String> OPTIONAL_KEYS = List.of("christian.CARNIVAL", "christian.ASH_WEDNESDAY");

    /** The Monday of Carnival: the XML knows the Tuesday only. */
    private static final long CARNIVAL_MONDAY_BEFORE_EASTER = 48;

    /** Names on the screen: jollyday's are long and one is broken ("Fiéis Defuntos"). */
    private static final Map<String, String> NAME = Map.ofEntries(
        Map.entry("NEW_YEAR", "Confraternização Universal"),
        Map.entry("FOUNDATION", "Fundação de São Paulo (cidade)"),
        Map.entry("christian.CARNIVAL", "Terça-feira de Carnaval"),
        Map.entry("christian.ASH_WEDNESDAY", "Quarta-feira de Cinzas (até 12h)"),
        Map.entry("christian.GOOD_FRIDAY", "Sexta-feira Santa"),
        Map.entry("christian.EASTER", "Domingo de Páscoa"),
        Map.entry("TIRADENTES", "Tiradentes"),
        Map.entry("LABOUR_DAY", "Dia do Trabalho"),
        Map.entry("christian.CORPUS_CHRISTI", "Corpus Christi"),
        Map.entry("CONST_REVOLUTION", "Revolução de 1932 (São Paulo)"),
        Map.entry("INDEPENDENCE_DAY", "Independência"),
        Map.entry("APARECIDA", "Nossa Senhora Aparecida"),
        Map.entry("ALL_SOULS", "Finados"),
        Map.entry("NATIONAL_DAY", "Proclamação da República"),
        Map.entry("NATIONAL_DAY_OF_ZUMBI_AND_BLACK_AWARENESS", "Dia da Consciência Negra"),
        Map.entry("BLACK_AWARENESS", "Dia da Consciência Negra (São Paulo)"),
        Map.entry("CHRISTMAS", "Natal"));

    /** "" on a working day; otherwise what to say after the date. */
    public static String off(LocalDate d) {
        Off o = of(d.getYear()).get(d);
        if (o == null) return weekend(d) ? "fim de semana" : "";
        return o.text() + (weekend(d) ? "   ·   fim de semana" : "");
    }

    static boolean weekend(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY;
    }

    /** The next `n` days off from today on, for `c config`. */
    public static List<String> next(int n) {
        List<String> r = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int step = 0; step < 2 && r.size() < n; step++) {
            for (var e : of(today.getYear() + step).entrySet()) {
                if (!e.getKey().isBefore(today)) r.add(Str.d(e.getKey()) + "   " + e.getValue().text());
            }
        }
        return r.stream().limit(n).toList();
    }

    /** The user's own days off; the list is rebuilt because these win over all. */
    public static void extra(List<LocalDate> days) { EXTRA = List.copyOf(days); YEARS.clear(); }

    public static List<LocalDate> extra() { return EXTRA; }

    private static Map<LocalDate, Off> of(int year) {
        Map<LocalDate, Off> hit = YEARS.get(year);
        if (hit != null) return hit;
        TreeMap<LocalDate, Off> t = new TreeMap<>();
        LocalDate easter = null;
        for (Holiday h : holidays(year)) {
            if (h.getPropertiesKey().endsWith(".EASTER")) easter = h.getDate();
            t.putIfAbsent(h.getDate(),
                new Off(NAME.getOrDefault(h.getPropertiesKey(), h.getDescription()), OPTIONAL_KEYS.contains(h.getPropertiesKey())));
        }
        for (Fix f : MISSING) t.putIfAbsent(f.in(year), new Off(f.label(), f.optional()));
        if (easter != null) t.putIfAbsent(easter.minusDays(CARNIVAL_MONDAY_BEFORE_EASTER), new Off("Segunda-feira de Carnaval", true));
        for (LocalDate d : EXTRA) t.put(d, new Off("sua folga", false));
        Map<LocalDate, Off> made = Collections.unmodifiableSortedMap(t);
        YEARS.put(year, made);
        return made;
    }

    /** jollyday, or an empty year: a calendar that will not load must not stop a punch. */
    private static List<Holiday> holidays(int year) {
        try {
            HolidayManager hm = HolidayManager.getInstance(ManagerParameters.create(BR));
            return hm.getHolidays(Year.of(year), SP.toArray(new String[0])).stream()
                .filter(h -> h.getType() != HolidayType.BANK_HOLIDAY)      // a bank day is not a day off
                .sorted().toList();
        } catch (RuntimeException | Error e) {
            return List.of();
        }
    }
}
