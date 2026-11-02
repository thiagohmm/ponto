package ponto;

import ponto.model.Db;
import ponto.model.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;

/**
 * The "dia normal" flag of the banco de horas: a day off marked normal is
 * ruled as a working day (batido - jornada), an empty one stays silent, the
 * falta still wins, and the plan counts the day again. The checks use past
 * national holidays so the result does not drift with today's date. Run by
 * test/smoke.sh:
 *
 *   java -cp target/classes:$(cat target/cp.txt):target/test-out ponto.Normal <db>
 */
public final class Normal {

    private static int ok = 0, bad = 0;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.out.println("x   need <db>"); System.exit(2); }
        var dbFile = java.nio.file.Path.of(args[0]);
        if (dbFile.toFile().exists()) java.nio.file.Files.deleteIfExists(dbFile);
        try (Db db = Db.open(args[0])) {
            Service s = new Service(db);
            s.setConfig(null, null, "8", false, x -> { });

            LocalDate natl = LocalDate.of(2024, 12, 25);   // Christmas national
            LocalDate eve = LocalDate.of(2024, 12, 24);    // optional (Christmas Eve)
            LocalDate sat = LocalDate.of(2024, 12, 28);    // a plain Saturday
            LocalDate ny = LocalDate.of(2025, 1, 1);       // New Year

            // a day off banks everything until marked normal
            s.create(natl, LocalTime.of(8, 0), LocalTime.of(18, 0), "feriado trabalhado", false, true, true);
            check("feriado 10h desmarcado: tudo banco (+10:00)", s.monthSaldo(YearMonth.of(2024, 12)) == 600);
            s.normal(natl, true);
            check("feriado 10h marcado normal: batido - jornada (+2:00)", s.monthSaldo(YearMonth.of(2024, 12)) == 120);
            check("flag visível no dia", s.normal(natl));

            // an empty day off marked normal contributes nothing
            s.normal(eve, true);
            check("dia off vazio marcado: saldo imutável", s.monthSaldo(YearMonth.of(2024, 12)) == 120);
            s.normal(sat, true);
            check("sábado vazio marcado: saldo imutável", s.monthSaldo(YearMonth.of(2024, 12)) == 120);

            // with punches, the falta still wins over the normal flag
            s.create(ny, LocalTime.of(8, 0), LocalTime.of(10, 0), "feriado curto", false, true, true);
            check("jan: feriado 2h desmarcado (+2:00)", s.monthSaldo(YearMonth.of(2025, 1)) == 120);
            s.normal(ny, true);
            s.falta(ny, true);
            check("falta ganha da normal: batido - jornada", s.monthSaldo(YearMonth.of(2025, 1)) == 120 - 480);

            check("warn fala da falta", s.normalWarn(ny).contains("falta ganha"));
            check("warn em dia útil", s.normalWarn(LocalDate.of(2025, 1, 2)).contains("não é dia off"));
            check("warn em dia off", s.normalWarn(eve).contains("pagam a jornada"));

            // the plan counts a flagged day off as a workday again
            YearMonth ym = YearMonth.now();
            long before = s.bank(ym).remaining();
            int flagged = 0;
            for (LocalDate d = LocalDate.now(); !d.isAfter(ym.atEndOfMonth()); d = d.plusDays(1)) {
                if (!s.holiday(d).isEmpty() && !s.falta(d)) {
                    s.normal(d, true);
                    flagged++;
                }
            }
            check("plano: " + flagged + " dia(s) off marcado(s) entram em remaining",
                  flagged == 0 || s.bank(ym).remaining() == before + flagged);
        }
        if (bad > 0) { System.out.println("FAIL  normal  " + bad + " check(s) falharam"); System.exit(1); }
        System.out.println("PASS  normal  " + ok + " checks");
    }

    private static void check(String what, boolean held) {
        if (held) { ok++; System.out.println("  ok  " + what); }
        else { bad++; System.out.println("  x   " + what); }
    }
}
