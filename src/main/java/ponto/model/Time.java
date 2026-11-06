package ponto.model;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;

/** One clock for CLI, web, parsers and calendar; scoped overrides aid deterministic tests. */
public final class Time {
    private static final Clock SYSTEM = Clock.system(ZoneId.of(
            System.getenv().getOrDefault("PONTO_TZ", "America/Sao_Paulo")));
    private static final ThreadLocal<Clock> OVERRIDE = new ThreadLocal<>();

    private Time() { }
    public static Clock clock() { return OVERRIDE.get() == null ? SYSTEM : OVERRIDE.get(); }
    public static LocalDate today() { return LocalDate.now(clock()); }
    public static LocalTime now() { return LocalTime.now(clock()); }
    public static LocalDateTime stamp() { return LocalDateTime.now(clock()); }
    public static YearMonth month() { return YearMonth.now(clock()); }
    public static AutoCloseable using(Clock clock) {
        Clock previous = OVERRIDE.get();
        OVERRIDE.set(clock);
        return () -> { if (previous == null) OVERRIDE.remove(); else OVERRIDE.set(previous); };
    }
}
