package ponto.app;

/** JSON shape of day_digest payload (POST /api/diario/resumo). */
public record DigestView(
        String diaIso,
        String summary,
        String facts,
        String model,
        String crea,
        String upd
) {}
