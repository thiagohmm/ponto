package ponto.model;

import ponto.Problema;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Versioned upgrades on Postgres. The schema is created in one atomic
 * transaction: Postgres DDL rolls back with everything else if it fails.
 *
 * This port of the app only ever sees a fresh database (the SQLite data was
 * moved in a one-off load), so it creates the v5 schema directly.
 */
final class Migrations {
    static final int VERSION = 5;

    static void apply(Db db) {
        int version = 0;
        if (db.hasTable("schema_migrations")) version = db.migrationVersion();
        if (version > VERSION) throw new Problema("banco de uma versão mais recente; restaure o backup para voltar");
        if (version == VERSION) return;
        if (version > 0) throw new Problema("banco PostgreSQL da versão " + version + "; migre para a v" + VERSION + " manualmente");
        db.transaction(() -> {
            schema(db);
            db.exec("CREATE TABLE schema_migrations (version INTEGER NOT NULL PRIMARY KEY)");
            db.exec("INSERT INTO schema_migrations(version) VALUES(" + VERSION + ")");
            // One active company per database: seed it from cfg (worker name),
            // mirroring the old SQLite migrateCompanies().
            seedCompany(db);
            return null;
        });
    }

    private static void seedCompany(Db db) {
        String name = db.cfg("who");
        if (name.isEmpty()) name = "Empresa";
        try (var q = db.conn().prepareStatement(
                "INSERT INTO company(name, off, daily) VALUES(?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            q.setString(1, name);
            q.setString(2, db.cfg("off"));
            q.setString(3, db.cfg("daily"));
            q.executeUpdate();
            int id = 0;
            try (var ks = q.getGeneratedKeys()) { if (ks.next()) id = ks.getInt(1); }
            db.setCfg("company", String.valueOf(id));
            seedKanbanColumns(db, id);
        } catch (SQLException e) { throw new Problema("migração: " + e.getMessage()); }
    }

    private static void schema(Db db) {
        String created = "crea TEXT NOT NULL DEFAULT to_char(now(), 'YYYY-MM-DD HH24:MI')";
        String company = "company_id INTEGER NOT NULL REFERENCES company(id)";

        db.exec("CREATE TABLE company ("
                + "id BIGSERIAL PRIMARY KEY, name TEXT NOT NULL DEFAULT '', "
                + "off TEXT NOT NULL DEFAULT '', daily TEXT NOT NULL DEFAULT '', " + created + ")");

        db.exec("CREATE TABLE ponto ("
                + "id BIGSERIAL PRIMARY KEY, dia TEXT NOT NULL, tin TEXT, tout TEXT, "
                + "just TEXT NOT NULL DEFAULT '', " + created + ", " + company + ")");
        db.exec("CREATE INDEX ix_ponto_dia ON ponto(dia)");
        db.exec("CREATE INDEX ix_ponto_company ON ponto(company_id, dia)");

        db.exec("CREATE TABLE dia_flag ("
                + company + ", dia TEXT NOT NULL, flag TEXT NOT NULL DEFAULT 'falta' "
                + "CHECK(flag IN ('falta','normal')), PRIMARY KEY (company_id, dia))");

        db.exec("CREATE TABLE cfg (k TEXT PRIMARY KEY, v TEXT NOT NULL DEFAULT '')");

        db.exec("CREATE TABLE folder ("
                + "id BIGSERIAL PRIMARY KEY, parent INTEGER, name TEXT NOT NULL DEFAULT '', "
                + created + ", " + company + ", UNIQUE(id, company_id), "
                + "FOREIGN KEY(parent, company_id) REFERENCES folder(id, company_id))");

        db.exec("CREATE TABLE note ("
                + "id BIGSERIAL PRIMARY KEY, title TEXT NOT NULL DEFAULT '', body TEXT NOT NULL DEFAULT '', "
                + created + ", upd TEXT NOT NULL DEFAULT to_char(now(), 'YYYY-MM-DD HH24:MI'), "
                + "folder_id INTEGER, " + company + ", revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0), "
                + "UNIQUE(id, company_id), FOREIGN KEY(folder_id, company_id) REFERENCES folder(id, company_id))");

        db.exec("CREATE TABLE tag ("
                + "id BIGSERIAL PRIMARY KEY, name TEXT NOT NULL DEFAULT '', color TEXT NOT NULL DEFAULT '', "
                + created + ", " + company + ")");

        db.exec("CREATE TABLE note_tag ("
                + "note_id INTEGER NOT NULL REFERENCES note(id) ON DELETE CASCADE, "
                + "tag_id INTEGER NOT NULL REFERENCES tag(id) ON DELETE CASCADE, "
                + "PRIMARY KEY(note_id, tag_id))");

        db.exec("CREATE TABLE note_file ("
                + "id BIGSERIAL PRIMARY KEY, note_id INTEGER NOT NULL REFERENCES note(id) ON DELETE CASCADE, "
                + "name TEXT NOT NULL DEFAULT '', key TEXT NOT NULL UNIQUE, "
                + "size INTEGER NOT NULL DEFAULT 0 CHECK(size >= 0), ctype TEXT NOT NULL DEFAULT '', "
                + created + ", state TEXT NOT NULL DEFAULT 'ready' CHECK(state IN ('uploading','ready','failed')))");

        db.exec("CREATE TABLE kanban_column ("
                + "id BIGSERIAL PRIMARY KEY, name TEXT NOT NULL DEFAULT '', "
                + "position INTEGER NOT NULL DEFAULT 0, " + created + ", " + company
                + ", UNIQUE(id, company_id))");

        db.exec("CREATE TABLE kanban_card ("
                + "id BIGSERIAL PRIMARY KEY, column_id INTEGER NOT NULL, "
                + "title TEXT NOT NULL DEFAULT '', body TEXT NOT NULL DEFAULT '', "
                + "done TEXT NOT NULL DEFAULT '', position INTEGER NOT NULL DEFAULT 0, "
                + "revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0), "
                + created + ", upd TEXT NOT NULL DEFAULT to_char(now(), 'YYYY-MM-DD HH24:MI'), "
                + company + ", UNIQUE(id, company_id), "
                + "FOREIGN KEY(column_id, company_id) REFERENCES kanban_column(id, company_id))");

        db.exec("CREATE TABLE kanban_block ("
                + "id BIGSERIAL PRIMARY KEY, card_id INTEGER NOT NULL, kind TEXT NOT NULL, "
                + "reason TEXT NOT NULL, " + created + ", cleared TEXT, " + company + ", "
                + "UNIQUE(id, company_id), FOREIGN KEY(card_id, company_id) REFERENCES kanban_card(id, company_id))");

        db.exec("CREATE TABLE kanban_card_file ("
                + "id BIGSERIAL PRIMARY KEY, card_id INTEGER NOT NULL REFERENCES kanban_card(id) ON DELETE CASCADE, "
                + "name TEXT NOT NULL DEFAULT '', key TEXT NOT NULL UNIQUE, "
                + "size INTEGER NOT NULL DEFAULT 0 CHECK(size >= 0), ctype TEXT NOT NULL DEFAULT '', "
                + created + ", state TEXT NOT NULL DEFAULT 'ready' CHECK(state IN ('uploading','ready','failed')))");

        db.exec("CREATE TABLE object_job ("
                + "key TEXT PRIMARY KEY, operation TEXT NOT NULL CHECK(operation IN ('PUT','DELETE')), "
                + "path TEXT NOT NULL DEFAULT '', ctype TEXT NOT NULL DEFAULT '', "
                + "attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT NOT NULL DEFAULT '')");

        // Indexes
        db.exec("CREATE INDEX ix_note_upd ON note(upd)");
        db.exec("CREATE INDEX ix_note_company ON note(company_id, upd)");
        db.exec("CREATE INDEX ix_note_folder ON note(folder_id)");
        db.exec("CREATE INDEX ix_folder_parent ON folder(parent)");
        db.exec("CREATE INDEX ix_folder_company ON folder(company_id, parent)");
        db.exec("CREATE INDEX ix_tag_company ON tag(company_id)");
        db.exec("CREATE INDEX ix_note_tag_tag ON note_tag(tag_id)");
        db.exec("CREATE INDEX ix_note_file_note ON note_file(note_id)");
        db.exec("CREATE INDEX ix_kanban_column_company ON kanban_column(company_id, position)");
        db.exec("CREATE INDEX ix_kanban_card_column ON kanban_card(company_id, column_id, position)");
        db.exec("CREATE INDEX ix_kanban_block_card ON kanban_block(company_id, card_id, id)");
        db.exec("CREATE UNIQUE INDEX ux_kanban_block_open ON kanban_block(company_id, card_id) WHERE cleared IS NULL");
        db.exec("CREATE INDEX ix_kanban_card_file_card ON kanban_card_file(card_id)");

        // note_tag links a note to a tag: both must belong to the same company.
        db.exec("CREATE FUNCTION check_note_tag_company() RETURNS trigger AS $$ "
                + "BEGIN "
                + "IF (SELECT company_id FROM note WHERE id = NEW.note_id) != (SELECT company_id FROM tag WHERE id = NEW.tag_id) "
                + "THEN RAISE EXCEPTION 'tag de outra empresa'; END IF; "
                + "RETURN NEW; END $$ LANGUAGE plpgsql");
        db.exec("CREATE TRIGGER trg_note_tag_company BEFORE INSERT ON note_tag "
                + "FOR EACH ROW EXECUTE FUNCTION check_note_tag_company()");
    }

    static void seedKanbanColumns(Db db, int companyId) {
        try (var q = db.conn().prepareStatement("SELECT count(*) FROM kanban_column WHERE company_id=?")) {
            q.setInt(1, companyId);
            try (var rs = q.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) return;
            }
        } catch (SQLException e) { throw new Problema("migração: " + e.getMessage()); }
        String[] names = { "A fazer", "Em andamento", "Feito" };
        try (var q = db.conn().prepareStatement(
                "INSERT INTO kanban_column(name, position, company_id) VALUES(?,?,?)")) {
            for (int i = 0; i < names.length; i++) {
                q.setString(1, names[i]);
                q.setInt(2, i);
                q.setInt(3, companyId);
                q.executeUpdate();
            }
        } catch (SQLException e) { throw new Problema("migração: " + e.getMessage()); }
    }
}
