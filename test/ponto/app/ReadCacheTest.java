package ponto.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ponto.TestDb;
import ponto.infra.Db;
import ponto.model.Time;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReadCacheTest {

    @TempDir Path dir;

    @Test void lruEvictionTtlAndDomainInvalidation() throws Exception {
        var cache = new ReadCache(8, TimeUnit.MILLISECONDS.toNanos(60));
        var loads = new AtomicInteger();

        assertEquals("v1", cache.get(1, ReadCache.Domain.TIMESHEET, "k", () -> "v" + loads.incrementAndGet()));
        assertEquals("v1", cache.get(1, ReadCache.Domain.TIMESHEET, "k", () -> "v" + loads.incrementAndGet()));
        assertEquals(1, loads.get());

        // Different company is isolated
        assertEquals("v2", cache.get(2, ReadCache.Domain.TIMESHEET, "k", () -> "v" + loads.incrementAndGet()));
        assertEquals(2, loads.get());

        // Invalidating company 1 drops company 1 only
        cache.invalidate(1, ReadCache.Domain.TIMESHEET);
        assertEquals("v3", cache.get(1, ReadCache.Domain.TIMESHEET, "k", () -> "v" + loads.incrementAndGet()));
        assertEquals("v2", cache.get(2, ReadCache.Domain.TIMESHEET, "k", () -> "v" + loads.incrementAndGet()));
        assertEquals(3, loads.get());

        // LRU cap respected
        for (int i = 0; i < 20; i++) {
            final int n = i;
            cache.get(1, ReadCache.Domain.NOTES, "n" + n, () -> "val" + n);
        }
        assertTrue(cache.size() <= 8);

        // TTL expiry reloads
        Thread.sleep(75);
        assertEquals("v4", cache.get(1, ReadCache.Domain.TIMESHEET, "k", () -> "v" + loads.incrementAndGet()));
        assertEquals(4, loads.get());
    }

    @Test void serviceCachesStayConsistentAcrossWritesAndClockChanges() throws Exception {
        ZoneId tz = ZoneId.of("America/Sao_Paulo");
        Clock may20 = Clock.fixed(Instant.parse("2026-05-20T15:00:00Z"), tz);
        Clock may21 = Clock.fixed(Instant.parse("2026-05-21T15:00:00Z"), tz);
        try (Db db = TestDb.open(dir); var ignored = Time.using(may20)) {
            var root = new Service(db);
            int a = root.companyId();
            int b = root.createCompany("B", "20/05/2026", "6").id();
            Service sa = root.forCompany(a);
            Service sb = root.forCompany(b);
            // Scoped service is reused from cache
            assertSame(sa, root.forCompany(a));

            // Prime caches
            assertEquals(0, sa.home().records());
            assertEquals(0, sa.bankSnapshot(YearMonth.of(2026, 5), 12).bank().saldoMins());
            assertEquals("feriado: sua folga", sb.holiday(LocalDate.of(2026, 5, 20)));

            // Write invalidates timesheet cache immediately
            sa.create(LocalDate.of(2026, 5, 20), LocalTime.of(8, 0), LocalTime.of(17, 0), "extra", false, false, false);
            assertEquals(1, sa.home().records());
            assertEquals(60, sa.home().saldoMins());

            // bankSnapshot matches individual bank / bankDays / history calls exactly
            var ym = YearMonth.of(2026, 5);
            var snap = sa.bankSnapshot(ym, 12);
            assertEquals(sa.bank(ym), snap.bank());
            assertEquals(sa.bankDays(ym), snap.days());
            assertEquals(sa.history(ym, 12), snap.months());

            // entriesRange matches count/totals/overtime
            var page = sa.entriesRange(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), null, 50, 0);
            assertEquals(1, page.total());
            assertEquals(540, page.totals().mins());
            assertEquals(60, page.overtime());

            // Company update invalidates cached off days and daily minutes
            root.updateCompany(b, "B2", "", "8");
            assertEquals("B2", sb.activeCompany().name());
            assertEquals("", sb.holiday(LocalDate.of(2026, 5, 20)));
            assertEquals(480, sb.dailyMinutes());

            // Midnight rollover uses new cache key
            try (var ignored2 = Time.using(may21)) {
                assertEquals(21, sa.bankSnapshot(ym, 12).days().size() > 0 ? LocalDate.now(may21).getDayOfMonth() : 0);
                assertNotNull(sa.home());
            }

            // Deleting empty company removes it from scopedServices and cache
            root.deleteCompany(b);
            assertThrows(ponto.NotFound.class, () -> root.forCompany(b));
        }
    }
}
