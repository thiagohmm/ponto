package ponto.app;

/** JSON shape of GET /api/home — field names are the API contract. */
public record HomeView(
        String who,
        String company,
        int companyId,
        int records,
        long monthMins,
        String monthLabel,
        String monthHours,
        String saldo,
        long saldoMins,
        String planLabel,
        boolean bankNeg,
        int open,
        String openSince,
        String db,
        String min,
        String max,
        String from,
        String to
) {}
