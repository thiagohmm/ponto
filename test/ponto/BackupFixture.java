package ponto;

import ponto.model.Db;
import ponto.model.Service;

import java.nio.file.Path;

/**
 * Test fixture for test/test_backup.py: creates the v5 schema (via Migrations,
 * through the app itself, exactly as production does) plus a real note in the
 * database/schema pointed to by the given JDBC URL.
 *
 *   java -cp <classes> ponto.BackupFixture <jdbc-url>
 */
public final class BackupFixture {
    public static void main(String[] args) {
        if (args.length < 1) { System.out.println("x need <jdbc-url>"); System.exit(2); }
        try (Db db = Db.open(Path.of(System.getProperty("java.io.tmpdir")), args[0])) {
            new Service(db).notes().create("nota de backup", "corpo preservado pelo backup");
            System.out.println("fixture ok");
        }
    }
}
