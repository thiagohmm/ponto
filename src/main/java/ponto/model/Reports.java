package ponto.model;

import java.time.LocalDate;
import java.util.function.Supplier;

/** Report assembly shared by terminal, HTTP summaries and all export formats. */
final class Reports {
    private final Db db;
    private final Repo repo;
    private final Supplier<String> who;
    private final Supplier<String> company;
    Reports(Db db, Repo repo, Supplier<String> who, Supplier<String> company) {
        this.db = db; this.repo = repo; this.who = who; this.company = company;
    }
    Report create(LocalDate from, LocalDate to, long balance, String plan) {
        int faltas = (int) repo.flags("falta", "%").stream()
                .filter(day -> !day.isBefore(from) && !day.isAfter(to)).count();
        var now = Time.stamp();
        String stamp = Str.d(now.toLocalDate()) + " " + Str.t(now.toLocalTime());
        return new Report(new Report.Head(who.get(), company.get(), db.file.toString(), new Period(from,to), stamp),
                repo.between(from,to), balance, faltas, plan);
    }
}
