package ponto.infra;

import ponto.Problema;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** One JSON collection blob per company for the local ponto-http client. */
public final class HttpClientStore {

    private final Db db;
    private final Object lock;

    public HttpClientStore(Db db, Object lock) {
        this.db = db;
        this.lock = lock;
    }

    private int cid() { return db.company(); }

    public Map<String, Object> load() {
        synchronized (lock) {
            try (var q = db.conn().prepareStatement(
                    "SELECT blob, revision, upd FROM http_client_collection WHERE company_id=?")) {
                q.setInt(1, cid());
                try (var rs = q.executeQuery()) {
                    if (!rs.next()) {
                        Map<String, Object> empty = new LinkedHashMap<>();
                        empty.put("name", "Minha collection");
                        empty.put("activeId", null);
                        empty.put("items", java.util.List.of());
                        empty.put("revision", 0L);
                        empty.put("upd", "");
                        return empty;
                    }
                    String blob = rs.getString(1);
                    long revision = rs.getLong(2);
                    String upd = rs.getString(3);
                    Map<String, Object> data = parse(blob);
                    data.put("revision", revision);
                    data.put("upd", upd == null ? "" : upd);
                    return data;
                }
            } catch (SQLException e) {
                throw new Problema("http-client: " + Db.clean(e.getMessage()));
            }
        }
    }

    public Map<String, Object> save(String blobJson) {
        final String blob = (blobJson == null || blobJson.isBlank()) ? "{}" : blobJson;
        // validate JSON
        parse(blob);
        final long revision = System.currentTimeMillis();
        synchronized (lock) {
            return db.transaction(() -> {
                try (var q = db.conn().prepareStatement(
                        "INSERT INTO http_client_collection(company_id, blob, revision) VALUES(?,?,?) "
                                + "ON CONFLICT (company_id) DO UPDATE SET "
                                + "blob=EXCLUDED.blob, revision=EXCLUDED.revision, "
                                + "upd=to_char(now(), 'YYYY-MM-DD HH24:MI')")) {
                    q.setInt(1, cid());
                    q.setString(2, blob);
                    q.setLong(3, revision);
                    q.executeUpdate();
                } catch (SQLException e) {
                    throw new Problema("http-client: " + Db.clean(e.getMessage()));
                }
                return load();
            });
        }
    }

    private static final tools.jackson.databind.json.JsonMapper OM =
            new tools.jackson.databind.json.JsonMapper();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String blob) {
        try {
            Map<String, Object> data = OM.readValue(blob, Map.class);
            return data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
        } catch (Exception e) {
            throw new ponto.Bad("JSON da collection inválido.");
        }
    }
}
