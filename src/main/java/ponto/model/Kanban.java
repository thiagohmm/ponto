package ponto.model;

import ponto.Problema;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-company kanban board. Shares Service's lock: the JDBC connection is not thread-safe.
 */
public final class Kanban {

    private static final String COL_COLS = "id, name, position, crea";
    private static final String CARD_COLS = "id, column_id, title, body, done, position, revision, crea, upd";

    private final Db db;
    private final Object lock;

    Kanban(Db db, Object lock) { this.db = db; this.lock = lock; }

    private int cid() { return db.company(); }

    public List<KanbanColumn> columns() {
        synchronized (lock) {
            return columnRows("SELECT " + COL_COLS + " FROM kanban_column WHERE company_id=? ORDER BY position, id", cid());
        }
    }

    public List<KanbanCard> cards() {
        synchronized (lock) {
            return cardsOf("SELECT " + CARD_COLS + " FROM kanban_card WHERE company_id=? ORDER BY column_id, position, id", cid());
        }
    }

    public KanbanColumn createColumn(String name) {
        synchronized (lock) {
            String n = trimName(name, "Coluna");
            int pos = nextColumnPosition();
            int id = insert("INSERT INTO kanban_column(name, position, company_id) VALUES(?,?,?)", n, pos, cid());
            return mustColumn(id);
        }
    }

    public KanbanColumn updateColumn(int id, String name, Integer position) {
        synchronized (lock) {
            return db.transaction(() -> {
                mustColumn(id);
                if (name != null) {
                    write("UPDATE kanban_column SET name=? WHERE id=? AND company_id=?",
                          trimName(name, "Coluna"), id, cid());
                }
                if (position != null) reorderColumns(id, position);
                return mustColumn(id);
            });
        }
    }

    public void deleteColumn(int id) {
        synchronized (lock) {
            db.transaction(() -> {
                KanbanColumn col = mustColumn(id);
                if (protectedColumn(col.name()))
                    throw new ponto.Bad("coluna padrão não pode ser apagada");
                write("DELETE FROM kanban_block WHERE company_id=? AND card_id IN "
                        + "(SELECT id FROM kanban_card WHERE column_id=? AND company_id=?)", cid(), id, cid());
                write("DELETE FROM kanban_card WHERE column_id=? AND company_id=?", id, cid());
                write("DELETE FROM kanban_column WHERE id=? AND company_id=?", id, cid());
                renumberColumns();
                return null;
            });
        }
    }

    public KanbanCard createCard(String title, String body, int columnId) {
        synchronized (lock) {
            mustColumn(columnId);
            int pos = nextCardPosition(columnId);
            int id = insert("INSERT INTO kanban_card(column_id, title, body, done, position, company_id) VALUES(?,?,?,?,?,?)",
                            columnId, nz(title), nz(body), "", pos, cid());
            return mustCard(id);
        }
    }

    public KanbanCard updateCard(int id, String title, String body, String done, int revision) {
        synchronized (lock) {
            mustCard(id);
            int changed = write("UPDATE kanban_card SET title=?, body=?, done=?, revision=revision+1,"
                  + " upd=strftime('%Y-%m-%d %H:%M:%f','now','localtime') WHERE id=? AND company_id=? AND revision=?",
                  nz(title), nz(body), nz(done), id, cid(), revision);
            if (changed == 0)
                throw new ponto.Conflict("Este cartão foi alterado em outro acesso. Recarregue e tente de novo.");
            return mustCard(id);
        }
    }

    /** Blocks a card that is not in Feito. The episode stays in the history. */
    public KanbanCard blockCard(int id, String kind, String reason) {
        synchronized (lock) {
            return db.transaction(() -> {
                KanbanCard card = mustCard(id);
                if (done(mustColumn(card.columnId()).name()))
                    throw new ponto.Bad("cartão em Feito não pode ser bloqueado");
                if (card.blocked()) throw new ponto.Bad("cartão já está bloqueado");
                KanbanBlockKind parsed = KanbanBlockKind.parse(kind);
                String why = reason == null ? "" : reason.trim();
                if (why.isEmpty()) throw new ponto.Bad("informe o motivo do bloqueio");
                if (why.length() > 500) throw new ponto.Bad("motivo longo demais");
                insert("INSERT INTO kanban_block(card_id, kind, reason, company_id) VALUES(?,?,?,?)",
                        id, parsed.code(), why, cid());
                return mustCard(id);
            });
        }
    }

    /** Closes the open block. Past episodes remain so the card can move again. */
    public KanbanCard unblockCard(int id) {
        synchronized (lock) {
            return db.transaction(() -> {
                KanbanCard card = mustCard(id);
                if (!card.blocked()) throw new ponto.Bad("cartão não está bloqueado");
                int changed = write("UPDATE kanban_block SET cleared=strftime('%Y-%m-%d %H:%M','now','localtime') "
                        + "WHERE card_id=? AND company_id=? AND cleared IS NULL", id, cid());
                if (changed == 0) throw new ponto.Bad("cartão não está bloqueado");
                return mustCard(id);
            });
        }
    }

    public KanbanCard moveCard(int id, int columnId, int position) {
        synchronized (lock) {
            return db.transaction(() -> {
                KanbanCard card = mustCard(id);
                mustColumn(columnId);
                if (card.blocked() && done(mustColumn(columnId).name()))
                    throw new ponto.Bad("cartão bloqueado não pode ir para Feito");
                int oldCol = card.columnId();
                List<KanbanCard> target = cardsInColumn(columnId).stream().filter(c -> c.id() != id).toList();
                int pos = Math.max(0, Math.min(position, target.size()));
                List<KanbanCard> next = new ArrayList<>(target);
                next.add(pos, card);
                for (int i = 0; i < next.size(); i++) {
                    write("UPDATE kanban_card SET column_id=?, position=? WHERE id=? AND company_id=?",
                          columnId, i, next.get(i).id(), cid());
                }
                if (oldCol != columnId) renumberCards(oldCol);
                return mustCard(id);
            });
        }
    }

    public void deleteCard(int id) {
        synchronized (lock) {
            db.transaction(() -> {
                KanbanCard card = mustCard(id);
                write("DELETE FROM kanban_block WHERE card_id=? AND company_id=?", id, cid());
                write("DELETE FROM kanban_card WHERE id=? AND company_id=?", id, cid());
                renumberCards(card.columnId());
                return null;
            });
        }
    }

    public KanbanCard byId(int id) {
        synchronized (lock) { return mustCard(id); }
    }

    private void reorderColumns(int id, int position) {
        List<KanbanColumn> all = columns().stream().filter(c -> c.id() != id).toList();
        int pos = Math.max(0, Math.min(position, all.size()));
        List<KanbanColumn> next = new ArrayList<>(all);
        next.add(pos, mustColumn(id));
        for (int i = 0; i < next.size(); i++)
            write("UPDATE kanban_column SET position=? WHERE id=? AND company_id=?", i, next.get(i).id(), cid());
    }

    private void renumberColumns() {
        List<KanbanColumn> all = columnRows(
                "SELECT " + COL_COLS + " FROM kanban_column WHERE company_id=? ORDER BY position, id", cid());
        for (int i = 0; i < all.size(); i++)
            write("UPDATE kanban_column SET position=? WHERE id=? AND company_id=?", i, all.get(i).id(), cid());
    }

    private void renumberCards(int columnId) {
        List<KanbanCard> all = cardsInColumn(columnId);
        for (int i = 0; i < all.size(); i++)
            write("UPDATE kanban_card SET position=? WHERE id=? AND company_id=?", i, all.get(i).id(), cid());
    }

    private List<KanbanCard> cardsInColumn(int columnId) {
        return cardRows("SELECT " + CARD_COLS + " FROM kanban_card WHERE company_id=? AND column_id=? ORDER BY position, id",
                        cid(), columnId);
    }

    private int nextColumnPosition() {
        try (var q = prep("SELECT COALESCE(MAX(position),-1)+1 FROM kanban_column WHERE company_id=?")) {
            q.setInt(1, cid());
            try (var rs = q.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException e) { throw new Problema(clean(e)); }
    }

    private int nextCardPosition(int columnId) {
        try (var q = prep("SELECT COALESCE(MAX(position),-1)+1 FROM kanban_card WHERE company_id=? AND column_id=?")) {
            q.setInt(1, cid());
            q.setInt(2, columnId);
            try (var rs = q.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException e) { throw new Problema(clean(e)); }
    }

    private KanbanColumn mustColumn(int id) {
        List<KanbanColumn> r = columnRows("SELECT " + COL_COLS + " FROM kanban_column WHERE id=? AND company_id=?", id, cid());
        if (r.isEmpty()) throw new ponto.NotFound("coluna " + id + " não existe");
        return r.getFirst();
    }

    private KanbanCard mustCard(int id) {
        List<KanbanCard> r = cardsOf("SELECT " + CARD_COLS + " FROM kanban_card WHERE id=? AND company_id=?", id, cid());
        if (r.isEmpty()) throw new ponto.NotFound("cartão " + id + " não existe");
        return r.getFirst();
    }

    private static String trimName(String name, String fallback) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) n = fallback;
        return n;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private List<KanbanColumn> columnRows(String sql, Object... args) {
        try (var q = prep(sql)) {
            bind(q, args);
            try (var rs = q.executeQuery()) {
                List<KanbanColumn> out = new ArrayList<>();
                while (rs.next()) out.add(mapColumn(rs));
                return out;
            }
        } catch (SQLException e) { throw new Problema(clean(e)); }
    }

    private List<KanbanCard> cardRows(String sql, Object... args) {
        try (var q = prep(sql)) {
            bind(q, args);
            try (var rs = q.executeQuery()) {
                List<KanbanCard> out = new ArrayList<>();
                while (rs.next()) out.add(mapCard(rs));
                return out;
            }
        } catch (SQLException e) { throw new Problema(clean(e)); }
    }

    private List<KanbanCard> cardsOf(String sql, Object... args) {
        return withBlocks(cardRows(sql, args));
    }

    private List<KanbanCard> withBlocks(List<KanbanCard> cards) {
        if (cards.isEmpty()) return cards;
        Map<Integer, List<KanbanBlock>> byCard = new LinkedHashMap<>();
        for (KanbanBlock block : blockRows(
                "SELECT id, card_id, kind, reason, crea, cleared FROM kanban_block WHERE company_id=? ORDER BY id DESC",
                cid())) {
            byCard.computeIfAbsent(block.cardId(), k -> new ArrayList<>()).add(block);
        }
        List<KanbanCard> out = new ArrayList<>();
        for (KanbanCard card : cards)
            out.add(card.withBlocks(byCard.getOrDefault(card.id(), List.of())));
        return out;
    }

    private List<KanbanBlock> blockRows(String sql, Object... args) {
        try (var q = prep(sql)) {
            bind(q, args);
            try (var rs = q.executeQuery()) {
                List<KanbanBlock> out = new ArrayList<>();
                while (rs.next()) out.add(mapBlock(rs));
                return out;
            }
        } catch (SQLException e) { throw new Problema(clean(e)); }
    }

    static String normalizeName(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    static boolean inProgress(String name) {
        return normalizeName(name).equals("em andamento");
    }

    static boolean done(String name) {
        return normalizeName(name).equals("feito");
    }

    static boolean protectedColumn(String name) {
        String n = normalizeName(name);
        return n.equals("a fazer") || n.equals("em andamento") || n.equals("feito");
    }

    private int write(String sql, Object... args) {
        try (var q = prep(sql)) {
            bind(q, args);
            return q.executeUpdate();
        } catch (SQLException e) { throw new Problema(clean(e)); }
    }

    private int insert(String sql, Object... args) {
        try (var q = prep(sql)) {
            bind(q, args);
            q.executeUpdate();
            try (var ks = q.getGeneratedKeys()) { return ks.next() ? ks.getInt(1) : 0; }
        } catch (SQLException e) { throw new Problema(clean(e)); }
    }

    private PreparedStatement prep(String sql) throws SQLException {
        return db.conn().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
    }

    private static void bind(PreparedStatement q, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            int n = i + 1;
            if (a == null) q.setNull(n, Types.INTEGER);
            else if (a instanceof Integer v) q.setInt(n, v);
            else q.setString(n, a.toString());
        }
    }

    private static KanbanColumn mapColumn(ResultSet rs) throws SQLException {
        return new KanbanColumn(rs.getInt("id"), nz(rs.getString("name")), rs.getInt("position"), nz(rs.getString("crea")));
    }

    private static KanbanCard mapCard(ResultSet rs) throws SQLException {
        return new KanbanCard(rs.getInt("id"), rs.getInt("column_id"), nz(rs.getString("title")),
                nz(rs.getString("body")), nz(rs.getString("done")), rs.getInt("position"), rs.getInt("revision"),
                nz(rs.getString("crea")), nz(rs.getString("upd")), List.of());
    }

    private static KanbanBlock mapBlock(ResultSet rs) throws SQLException {
        String kind = nz(rs.getString("kind"));
        String cleared = rs.getString("cleared");
        return new KanbanBlock(rs.getInt("id"), rs.getInt("card_id"), kind, KanbanBlockKind.labelOf(kind),
                nz(rs.getString("reason")), nz(rs.getString("crea")), cleared == null ? "" : cleared);
    }

    private static String clean(SQLException e) { return Db.clean(e.getMessage()); }
}
