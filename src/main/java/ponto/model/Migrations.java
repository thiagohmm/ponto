package ponto.model;

import ponto.Problema;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Versioned, atomic upgrades. A failed integrity check rolls back the entire upgrade. */
final class Migrations {
    static final int VERSION = 2;

    static void apply(Db db) {
        int version;
        try (var q = db.conn().createStatement(); var rs = q.executeQuery("PRAGMA user_version")) {
            version = rs.getInt(1);
        } catch (SQLException e) { throw new Problema("versão do banco: " + e.getMessage()); }
        if (version > VERSION) throw new Problema("banco de uma versão mais recente; restaure o backup para voltar");
        if (version == VERSION) return;
        // Rebuilding tables with foreign keys must happen with enforcement disabled,
        // outside the transaction; foreign_key_check still runs before committing.
        db.exec("PRAGMA foreign_keys=OFF");
        try {
            int from = version;
            db.transaction(() -> {
                if (from < 1) {
                    for (String sql : Db.SCHEMA) db.exec(sql);
                    if (!db.hasColumn("note", "folder_id")) db.exec("ALTER TABLE note ADD COLUMN folder_id INTEGER");
                    db.migrateCompanies();
                }
                if (from < 2) integritySchema(db);
                try (var q = db.conn().createStatement(); var rs = q.executeQuery("PRAGMA foreign_key_check")) {
                    if (rs.next()) throw new Problema("migração: referência inválida em " + rs.getString(1)
                            + ", registro " + rs.getString(2) + "; nenhum dado foi alterado");
                } catch (SQLException e) { throw new Problema("migração: " + e.getMessage()); }
                db.exec("PRAGMA user_version=" + VERSION);
                return null;
            });
        } finally { db.exec("PRAGMA foreign_keys=ON"); }
    }

    private static void integritySchema(Db db) {
        Map<String, String> tables = new LinkedHashMap<>();
        String created = "crea TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M','now','localtime'))";
        String company = "company_id INTEGER NOT NULL REFERENCES company(id)";
        tables.put("ponto", "id INTEGER PRIMARY KEY AUTOINCREMENT, dia TEXT NOT NULL, tin TEXT, tout TEXT, "
                + "just TEXT NOT NULL DEFAULT '', " + created + ", " + company);
        tables.put("folder", "id INTEGER PRIMARY KEY AUTOINCREMENT, parent INTEGER, name TEXT NOT NULL DEFAULT '', "
                + created + ", " + company + ", UNIQUE(id,company_id), FOREIGN KEY(parent,company_id) REFERENCES folder(id,company_id)");
        tables.put("note", "id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL DEFAULT '', body TEXT NOT NULL DEFAULT '', "
                + created + ", upd TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M','now','localtime')), folder_id INTEGER, "
                + company + ", revision INTEGER NOT NULL DEFAULT 1 CHECK(revision>0), UNIQUE(id,company_id), "
                + "FOREIGN KEY(folder_id,company_id) REFERENCES folder(id,company_id)");
        tables.put("tag", "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL DEFAULT '', color TEXT NOT NULL DEFAULT '', "
                + created + ", " + company);
        tables.put("note_tag", "note_id INTEGER NOT NULL REFERENCES note(id) ON DELETE CASCADE, "
                + "tag_id INTEGER NOT NULL REFERENCES tag(id) ON DELETE CASCADE, PRIMARY KEY(note_id,tag_id)");
        tables.put("note_file", "id INTEGER PRIMARY KEY AUTOINCREMENT, note_id INTEGER NOT NULL REFERENCES note(id) ON DELETE CASCADE, "
                + "name TEXT NOT NULL DEFAULT '', key TEXT NOT NULL UNIQUE, size INTEGER NOT NULL DEFAULT 0 CHECK(size>=0), "
                + "ctype TEXT NOT NULL DEFAULT '', " + created + ", state TEXT NOT NULL DEFAULT 'ready' CHECK(state IN ('uploading','ready','failed'))");
        tables.put("dia_flag", company + ", dia TEXT NOT NULL, flag TEXT NOT NULL CHECK(flag IN ('falta','normal')), PRIMARY KEY(company_id,dia)");
        for (var t : tables.entrySet()) rebuild(db, t.getKey(), t.getValue());
        db.exec("CREATE INDEX ix_ponto_company ON ponto(company_id,dia)");
        db.exec("CREATE INDEX ix_note_company ON note(company_id,upd)");
        db.exec("CREATE INDEX ix_note_folder ON note(folder_id)");
        db.exec("CREATE INDEX ix_folder_company ON folder(company_id,parent)");
        db.exec("CREATE INDEX ix_tag_company ON tag(company_id)");
        db.exec("CREATE INDEX ix_note_tag_tag ON note_tag(tag_id)");
        db.exec("CREATE INDEX ix_note_file_note ON note_file(note_id)");
        db.exec("""
            CREATE TABLE object_job (
              key TEXT PRIMARY KEY,
              operation TEXT NOT NULL CHECK(operation IN ('PUT','DELETE')),
              path TEXT NOT NULL DEFAULT '',
              ctype TEXT NOT NULL DEFAULT '',
              attempts INTEGER NOT NULL DEFAULT 0,
              last_error TEXT NOT NULL DEFAULT ''
            )""");
        // These links carry no company column, so enforce matching ownership at the boundary.
        for (String action : new String[]{"INSERT", "UPDATE"}) db.exec("CREATE TRIGGER note_tag_company_" + action.toLowerCase()
                + " BEFORE " + action + " ON note_tag WHEN (SELECT company_id FROM note WHERE id=NEW.note_id)"
                + " != (SELECT company_id FROM tag WHERE id=NEW.tag_id) BEGIN SELECT RAISE(ABORT,'tag de outra empresa'); END");
        try (var q = db.conn().createStatement(); var rs = q.executeQuery(
                "SELECT 1 FROM note_tag nt JOIN note n ON n.id=nt.note_id JOIN tag t ON t.id=nt.tag_id WHERE n.company_id!=t.company_id LIMIT 1")) {
            if (rs.next()) throw new Problema("migração: tag vinculada a outra empresa; nenhum dado foi alterado");
        } catch (SQLException e) { throw new Problema("migração: " + e.getMessage()); }
    }

    private static void rebuild(Db db, String table, String definition) {
        long sequence = 0;
        try (var q = db.conn().prepareStatement("SELECT seq FROM sqlite_sequence WHERE name=?")) {
            q.setString(1, table);
            try (var rs = q.executeQuery()) { if (rs.next()) sequence = rs.getLong(1); }
        } catch (SQLException e) { throw new Problema("migração: " + e.getMessage()); }
        var columns = new java.util.ArrayList<String>();
        try (var q = db.conn().createStatement(); var rs = q.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) columns.add(rs.getString("name"));
        } catch (SQLException e) { throw new Problema("migração: " + e.getMessage()); }
        String cols = String.join(",", columns);
        db.exec("CREATE TABLE " + table + "_upgrade (" + definition + ")");
        db.exec("INSERT INTO " + table + "_upgrade(" + cols + ") SELECT " + cols + " FROM " + table);
        db.exec("DROP TABLE " + table);
        db.exec("ALTER TABLE " + table + "_upgrade RENAME TO " + table);
        if (sequence > 0) db.exec("UPDATE sqlite_sequence SET seq=max(seq," + sequence + ") WHERE name='" + table + "'");
    }
}
