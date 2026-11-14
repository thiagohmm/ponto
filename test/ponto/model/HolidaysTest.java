package ponto.model;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The Brazilian calendar in Java: Easter anchor, mobile days, and the national,
 * state (sp) and city (csp) fixed days -- the same set jollyday used to load.
 */
class HolidaysTest {

    private final Holidays h = new Holidays(List::of);

    @Test void easterIsTheAnchor() {
        assertEquals(LocalDate.of(2024, 3, 31), Holidays.easter(2024));
        assertEquals(LocalDate.of(2025, 4, 20), Holidays.easter(2025));
        assertEquals(LocalDate.of(2026, 4, 5), Holidays.easter(2026));
        assertEquals(LocalDate.of(2027, 3, 28), Holidays.easter(2027));
        assertEquals(LocalDate.of(2028, 4, 16), Holidays.easter(2028));
    }

    @Test void mobileDaysWalkWithEaster() {
        assertEquals("feriado: Domingo de Páscoa   ·   fim de semana", h.off(LocalDate.of(2026, 4, 5)));
        assertEquals("feriado: Sexta-feira Santa", h.off(LocalDate.of(2026, 4, 3)));
        assertEquals("feriado: Corpus Christi", h.off(LocalDate.of(2026, 6, 4)));
        assertEquals("facultativo: Segunda-feira de Carnaval", h.off(LocalDate.of(2026, 2, 16)));
        assertEquals("facultativo: Terça-feira de Carnaval", h.off(LocalDate.of(2026, 2, 17)));
        assertEquals("facultativo: Quarta-feira de Cinzas (até 12h)", h.off(LocalDate.of(2026, 2, 18)));
        // 2025: Easter 20/04, Carnival 04/03, Corpus Christi 19/06
        assertEquals("facultativo: Terça-feira de Carnaval", h.off(LocalDate.of(2025, 3, 4)));
        assertEquals("feriado: Corpus Christi", h.off(LocalDate.of(2025, 6, 19)));
    }

    @Test void nationalStateAndCityDays() {
        assertEquals("feriado: Confraternização Universal", h.off(LocalDate.of(2026, 1, 1)));
        assertEquals("feriado: Tiradentes", h.off(LocalDate.of(2026, 4, 21)));
        assertEquals("feriado: Dia do Trabalho", h.off(LocalDate.of(2026, 5, 1)));
        assertEquals("feriado: Independência", h.off(LocalDate.of(2026, 9, 7)));
        assertEquals("feriado: Nossa Senhora Aparecida", h.off(LocalDate.of(2026, 10, 12)));
        assertEquals("feriado: Finados", h.off(LocalDate.of(2026, 11, 2)));
        assertEquals("feriado: Proclamação da República", h.off(LocalDate.of(2024, 11, 15)));
        assertEquals("feriado: Natal", h.off(LocalDate.of(2026, 12, 25)));
        assertEquals("feriado: Revolução de 1932 (São Paulo)", h.off(LocalDate.of(2026, 7, 9)));
        // the city day falls on a Sunday in 2026: the day off and the weekend, both
        assertEquals("feriado: Fundação de São Paulo (cidade)   ·   fim de semana",
                h.off(LocalDate.of(2026, 1, 25)));
    }

    @Test void november20thIsNationalSince2024AndStateBefore() {
        assertEquals("feriado: Dia da Consciência Negra", h.off(LocalDate.of(2026, 11, 20)));
        assertEquals("feriado: Dia da Consciência Negra (São Paulo)", h.off(LocalDate.of(2023, 11, 20)));
    }

    @Test void optionalDaysWorkToo() {
        assertEquals("facultativo: Véspera de Natal", h.off(LocalDate.of(2026, 12, 24)));
        assertEquals("facultativo: Véspera de Ano Novo", h.off(LocalDate.of(2026, 12, 31)));
        assertEquals("feriado: Confraternização Universal", h.off(LocalDate.of(2025, 12, 26)));
    }

    @Test void weekendsAndPlainWorkingDays() {
        assertEquals("fim de semana", h.off(LocalDate.of(2026, 2, 21)));
        assertEquals("fim de semana", h.off(LocalDate.of(2026, 2, 22)));
        assertEquals("", h.off(LocalDate.of(2026, 2, 20)));
    }

    @Test void usersOwnDaysOffWinAndAreListed() {
        var own = new Holidays(() -> List.of(LocalDate.of(2026, 10, 27)));
        assertEquals("feriado: sua folga", own.off(LocalDate.of(2026, 10, 27)));
        assertTrue(own.next(3).size() > 0);
    }
}
