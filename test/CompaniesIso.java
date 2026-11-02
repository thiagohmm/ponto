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
            System.out.println("PASS  companies isolation");
        }
    }

    private static void die(String m) {
        System.out.println("FAIL  " + m);
        System.exit(1);
    }
}
