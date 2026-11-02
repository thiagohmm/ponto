package ponto;

import ponto.model.Db;
import ponto.model.Service;

import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalTime;

/** Two companies must not share punches or notes. Run after compile. */
public final class CompaniesIso {
    public static void main(String[] args) throws Exception {
        var dbFile = java.nio.file.Path.of(args[0]);
        Files.deleteIfExists(dbFile);
        try (Db db = Db.open(args[0])) {
            Service s = new Service(db);
            var a = s.companies().active();
            s.create(LocalDate.of(2026, 5, 20), LocalTime.of(8, 0), LocalTime.of(12, 0), "a", false, true, true);
            s.notes().create("nota A", "corpo A");
            s.setConfig(null, "01/01/2026", "6", false, x -> {});
            var b = s.companies().create("Outra", "", "8");
            s.activate(b.id());
            s.create(LocalDate.of(2026, 6, 1), LocalTime.of(9, 0), LocalTime.of(10, 0), "b", false, true, true);
            s.notes().create("nota B", "corpo B");
            if (s.count() != 1) die("B count " + s.count());
            if (!s.notes().list("").getFirst().title().equals("nota B")) die("B notes");
            if (s.dailyMinutes() != 480) die("B daily " + s.dailyMinutes());
            try { s.byId(1); die("B saw A's punch"); } catch (Problema ignored) {}
            s.activate(a.id());
            if (s.count() != 1) die("A count " + s.count());
            if (!s.search("a").getFirst().just().equals("a")) die("A punch");
            if (!s.notes().list("").getFirst().title().equals("nota A")) die("A notes");
            if (s.dailyMinutes() != 360) die("A daily " + s.dailyMinutes());
            try { s.companies().delete(a.id()); die("deleted company with data"); } catch (Bad ignored) {}
            try { s.companies().delete(b.id()); die("deleted other company with data"); } catch (Bad ignored) {}
            var day = LocalDate.of(2026, 6, 1);
            s.create(day, LocalTime.of(10, 0), LocalTime.of(11, 0), "matching", false, true, true);
            s.create(day.plusDays(1), LocalTime.of(10, 0), LocalTime.of(11, 0), "matching", false, true, true);
            if (s.count("matching", "2026-06-01") != 1 || s.listTotals("matching", "2026-06-01").mins() != 60)
                die("combined text date and search");
            var filtered = s.filteredRange(day, day, "matching", 1, 0);
            if (filtered.total() != 1 || filtered.entries().size() != 1 || filtered.totals().mins() != 60)
                die("combined range and search totals");
            var paged = s.filteredRange(day, day.plusDays(1), "matching", 1, 1);
            if (paged.total() != 2 || paged.entries().size() != 1 || paged.totals().mins() != 120)
                die("filtered totals must cover all pages");
            if (s.filteredRange(day, day, "missing", 10, 0).total() != 0) die("empty search");
            s.activate(b.id());
            if (s.filteredRange(day, day.plusDays(1), "matching", 10, 0).total() != 0)
                die("filtered range leaked another company");
            System.out.println("PASS  companies isolation + combined filters");
        }
    }

    private static void die(String m) {
        System.out.println("FAIL  " + m);
        System.exit(1);
    }
}
