package ponto.app;

import java.util.List;

/** JSON shape of GET/PUT /api/config — field names are the API contract. */
public record ConfigView(
        String who,
        String company,
        int companyId,
        String off,
        String daily,
        int dailyMins,
        String db,
        int records,
        String from,
        String to,
        String java,
        String postgres,
        boolean storage,
        int pendingObjects,
        long uploadMib,
        List<String> nextOff
) {}
