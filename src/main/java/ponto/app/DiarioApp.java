package ponto.app;

import ponto.infra.Assist;
import ponto.infra.Db;
import ponto.infra.Diario;
import ponto.infra.Repo;
import ponto.model.Holidays;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/** Day digest: aggregate snapshot + AI/fallback summary. */
public final class DiarioApp {
    private final Db db;
    private final Object lock;
    private final Repo repo;
    private final Holidays calendar;
    private final IntSupplier dailyMins;
    private final Predicate<LocalDate> falta;
    private final Predicate<LocalDate> normal;

    DiarioApp(Db db, Object lock, Repo repo, Holidays calendar,
              IntSupplier dailyMins, Predicate<LocalDate> falta, Predicate<LocalDate> normal) {
        this.db = db;
        this.lock = lock;
        this.repo = repo;
        this.calendar = calendar;
        this.dailyMins = dailyMins;
        this.falta = falta;
        this.normal = normal;
    }

    private Diario diario() { return new Diario(db, lock, repo, calendar); }

    public Map<String, Object> snapshot(LocalDate dia) {
        return diario().snapshot(dia, dailyMins.getAsInt(), falta.test(dia), normal.test(dia));
    }

    public DigestView gerarResumo(LocalDate dia, Assist assist) {
        Diario d = diario();
        Map<String, Object> snap = d.snapshot(dia, dailyMins.getAsInt(), falta.test(dia), normal.test(dia));
        var entries = d.entriesOf(dia);
        String facts = d.factsText(snap, entries);
        Assist helper = assist == null ? Assist.env() : assist;
        Assist.DaySummary s = helper.summarizeDay(facts);
        StringBuilder body = new StringBuilder(s.summary() == null ? "" : s.summary().trim());
        if (s.bullets() != null) {
            for (String b : s.bullets()) {
                if (b == null || b.isBlank()) continue;
                body.append("\n- ").append(b.trim());
            }
        }
        String model = s.fromModel() ? helper.model() : "fallback";
        Diario.Digest dig = d.upsert(dia, body.toString().trim(), facts, model);
        return new DigestView(dig.diaIso(), dig.summary(), dig.facts(), dig.model(), dig.crea(), dig.upd());
    }
}
