package ponto.infra;

import ponto.Bad;
import ponto.Conflict;
import ponto.NotFound;
import ponto.Problema;
import ponto.model.*;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The vault's rows. The only SQL for the vault — and the one place that proves
 * the promise of the feature: the columns it writes are ciphertext and counters.
 *
 * Nothing here imports a cipher, opens a blob or looks at plaintext. If a
 * future change asks this file for a key, the design is already broken.
 *
 * The vault is <em>not</em> company-scoped (one master password, items tagged by
 * company): {@link #list} deliberately reads every company. The tag is a plain
 * integer, so the server can count, filter and refuse orphans without being able
 * to read a single credential.
 */
public final class VaultStore {

    private static final String COLS = "id, company_id, blob, revision, crea, upd";
    private final Db db;
    private final Object lock;

    public VaultStore(Db db, Object lock) { this.db = db; this.lock = lock; }

    private int cid() { return db.company(); }

    /** The vault's public parameters, or an unsealed one when it was never created. */
    public VaultMeta meta() {
        synchronized (lock) {
            try (var q = db.conn().prepareStatement(
                    "SELECT kdf, salt, iterations, check_blob, upd FROM vault_meta WHERE id = 1")) {
                try (var rs = q.executeQuery()) {
                    if (!rs.next()) return new VaultMeta(VaultCrypto.KDF, "", VaultCrypto.ITERATIONS, "", "", 0, false);
                    int items = count();
                    return new VaultMeta(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                            rs.getString(5), items, true);
                }
            } catch (SQLException e) { throw new Problema("cofre: " + Db.clean(e.getMessage())); }
        }
    }

    /**
     * Create the vault once. The salt and the verifier blob come from the client —
     * the server invents no key material, so it cannot "help" an offline attacker.
     * Re-running on an existing vault is a 409: the verifier never resets quietly.
     */
    public VaultMeta create(String kdf, String salt, int iterations, String check) {
        if (!VaultCrypto.KDF.equals(kdf)) throw new Bad("kdf unsupported: " + kdf);
        if (!VaultCrypto.looksSalt(salt)) throw new Bad("salt inválido");
        if (iterations < VaultCrypto.MIN_ITERATIONS || iterations > VaultCrypto.MAX_ITERATIONS)
            throw new Bad("interações PBKDF2 fora de range");
        if (!VaultCrypto.looksSealed(check)) throw new Bad("verificador inválido");
        synchronized (lock) {
            return db.transaction(() -> {
                if (meta().sealed()) throw new Conflict("cofre já created");
                try (var q = db.conn().prepareStatement(
                        "INSERT INTO vault_meta(id, kdf, salt, iterations, check_blob) VALUES(1,?,?,?,?)")) {
                    q.setString(1, kdf);
                    q.setString(2, salt);
                    q.setInt(3, iterations);
                    q.setString(4, check);
                    q.executeUpdate();
                } catch (SQLException e) { throw new Problema("cofre: " + Db.clean(e.getMessage())); }
                return meta();
            });
        }
    }

    /** Every credential, newest first — across companies, because the vault is one. */
    public List<VaultItem> list(Integer companyFilter) {
        synchronized (lock) {
            // 0 and below mean "every company", not company zero, which does not exist:
            // a caller that passed 0 and silently got an empty list would look like a
            // vault the user forgot they had
            StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM vault_item");
            List<Object> args = new ArrayList<>();
            if (companyFilter != null && companyFilter >= 1) { sql.append(" WHERE company_id = ?"); args.add(companyFilter); }
            sql.append(" ORDER BY upd DESC, id DESC");
            return rows(sql.toString(), args.toArray());
        }
    }

    public VaultItem byId(int id) {
        synchronized (lock) {
            var r = rows("SELECT " + COLS + " FROM vault_item WHERE id = ?", id);
            if (r.isEmpty()) throw new NotFound("credential " + id + " não existe");
            return r.getFirst();
        }
    }

    /** Store a new sealed record. The plaintext never passes through here. */
    public VaultItem create(int companyId, String blob) {
        if (!VaultCrypto.looksSealed(blob)) throw new Bad("cofre: record não selado");
        synchronized (lock) {
            return db.transaction(() -> {
                mustCompany(companyId);
                int id = insert("INSERT INTO vault_item(company_id, blob) VALUES(?,?)", companyId, blob);
                return must(id);
            });
        }
    }

    /** Rewrite one record; a stale revision is refused so nobody silently loses an edit. */
    public VaultItem update(int id, String blob, int revision) {
        if (!VaultCrypto.looksSealed(blob)) throw new Bad("cofre: record não selado");
        synchronized (lock) {
            return db.transaction(() -> {
                must(id);
                int changed = write("UPDATE vault_item SET blob = ?, revision = revision + 1, "
                        + "upd = to_char(now(), 'YYYY-MM-DD HH24:MI:SS.MS') WHERE id = ? AND revision = ?",
                        blob, id, revision);
                if (changed == 0)
                    throw new Conflict("Este credential foi alterado em outro access. Recargue e tente de novo.");
                return must(id);
            });
        }
    }

    public void delete(int id) {
        synchronized (lock) {
            db.transaction(() -> {
                must(id);
                write("DELETE FROM vault_item WHERE id = ?", id);
                return null;
            });
        }
    }

    /**
     * Re-key: seal the whole vault again under a new master password, atomically.
     * Every blob and the verifier are replaced in one transaction — a half-done
     * re-key would leave records that the new password cannot open.
     */
    public VaultMeta rekey(String kdf, String salt, int iterations, String check,
                           List<VaultRewrap> items) {
        if (!VaultCrypto.KDF.equals(kdf)) throw new Bad("kdf unsupported: " + kdf);
        if (!VaultCrypto.looksSalt(salt)) throw new Bad("salt inválido");
        if (iterations < VaultCrypto.MIN_ITERATIONS || iterations > VaultCrypto.MAX_ITERATIONS)
            throw new Bad("interações PBKDF2 fora de range");
        if (!VaultCrypto.looksSealed(check)) throw new Bad("verificador inválido");
        for (var r : items) if (!VaultCrypto.looksSealed(r.blob())) throw new Bad("cofre: record não selado");
        synchronized (lock) {
            return db.transaction(() -> {
                if (!meta().sealed()) throw new NotFound("cofre não created");
                for (var r : items) {
                    int changed = write("UPDATE vault_item SET blob = ?, revision = revision + 1, "
                            + "upd = to_char(now(), 'YYYY-MM-DD HH24:MI:SS.MS') WHERE id = ? AND revision = ?",
                            r.blob(), r.id(), r.revision());
                    if (changed == 0) throw new Conflict("Este credential foi alterado em outro access: rekey paro.");
                }
                try (var q = db.conn().prepareStatement(
                        "UPDATE vault_meta SET kdf = ?, salt = ?, iterations = ?, check_blob = ? WHERE id = 1")) {
                    q.setString(1, kdf);
                    q.setString(2, salt);
                    q.setInt(3, iterations);
                    q.setString(4, check);
                    q.executeUpdate();
                } catch (SQLException e) { throw new Problema("cofre: " + Db.clean(e.getMessage())); }
                return meta();
            });
        }
    }

    /** Add many records at once (an imported file, sealed with the current key). */
    public List<VaultItem> createAll(List<VaultDraft> drafts) {
        synchronized (lock) {
            return db.transaction(() -> {
                List<VaultItem> out = new ArrayList<>();
                for (var d : drafts) {
                    if (!VaultCrypto.looksSealed(d.blob())) throw new Bad("cofre: record não selado");
                    mustCompany(d.companyId());
                    int id = insert("INSERT INTO vault_item(company_id, blob) VALUES(?,?)", d.companyId(), d.blob());
                    out.add(must(id));
                }
                return out;
            });
        }
    }

    public int count() {
        synchronized (lock) {
            try (var q = db.conn().prepareStatement("SELECT count(*) FROM vault_item")) {
                try (var rs = q.executeQuery()) { rs.next(); return rs.getInt(1); }
            } catch (SQLException e) { throw new Problema("cofre: " + Db.clean(e.getMessage())); }
        }
    }

    public int countCompany(int companyId) {
        if (companyId < 1) return count();
        synchronized (lock) {
            try (var q = db.conn().prepareStatement("SELECT count(*) FROM vault_item WHERE company_id = ?")) {
                q.setInt(1, companyId);
                try (var rs = q.executeQuery()) { rs.next(); return rs.getInt(1); }
            } catch (SQLException e) { throw new Problema("cofre: " + Db.clean(e.getMessage())); }
        }
    }

    /** One blob per company, for the "wipe this company's credentials" prompt. */
    public List<Integer> idsOf(int companyId) {
        synchronized (lock) {
            List<Integer> out = new ArrayList<>();
            try (var q = db.conn().prepareStatement("SELECT id FROM vault_item WHERE company_id = ? ORDER BY id")) {
                q.setInt(1, companyId);
                try (var rs = q.executeQuery()) { while (rs.next()) out.add(rs.getInt(1)); }
            } catch (SQLException e) { throw new Problema("cofre: " + Db.clean(e.getMessage())); }
            return out;
        }
    }

    private VaultItem must(int id) {
        var r = rows("SELECT " + COLS + " FROM vault_item WHERE id = ?", id);
        if (r.isEmpty()) throw new NotFound("credential " + id + " não existe");
        return r.getFirst();
    }
    private void mustCompany(int companyId) {
        try (var q = db.conn().prepareStatement("SELECT 1 FROM company WHERE id = ?")) {
            q.setInt(1, companyId);
            try (var rs = q.executeQuery()) {
                if (!rs.next()) throw new NotFound("empresa " + companyId + " não existe");
            }
        } catch (NotFound e) { throw e; }
        catch (SQLException e) { throw new Problema("cofre: " + Db.clean(e.getMessage())); }
    }

    private int insert(String sql, Object... args) {
        try (var q = db.conn().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(q, args);
            q.executeUpdate();
            try (var ks = q.getGeneratedKeys()) { if (ks.next()) return ks.getInt(1); }
            throw new Problema("cofre: no id");
        } catch (SQLException e) { throw new Problema("cofre: " + Db.clean(e.getMessage())); }
    }

    /** Runs a write and returns how many rows it touched — 0 means the guard refused it. */
    private int write(String sql, Object... args) {
        try (var q = db.conn().prepareStatement(sql)) {
            bind(q, args);
            return q.executeUpdate();
        } catch (SQLException e) { throw new Problema("cofre: " + Db.clean(e.getMessage())); }
    }

    private List<VaultItem> rows(String sql, Object... args) {
        try (var q = db.conn().prepareStatement(sql)) {
            bind(q, args);
            return read(q.executeQuery());
        } catch (SQLException e) { throw new Problema("cofre: " + Db.clean(e.getMessage())); }
    }

    private List<VaultItem> read(ResultSet rs) throws java.sql.SQLException {
        List<VaultItem> out = new ArrayList<>();
        while (rs.next())
            out.add(new VaultItem(rs.getInt("id"), rs.getInt("company_id"), nz(rs.getString("blob")),
                    rs.getInt("revision"), nz(rs.getString("crea")), nz(rs.getString("upd"))));
        return out;
    }

    private void bind(PreparedStatement q, Object[] args) throws java.sql.SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) q.setNull(i + 1, java.sql.Types.INTEGER);
            else if (a instanceof Integer n) q.setInt(i + 1, n);
            else if (a instanceof String s) q.setString(i + 1, s);
            else throw new Problema("cofre: param " + i + " inválido");
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
