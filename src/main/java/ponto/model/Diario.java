package ponto.model;

import ponto.Problema;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Day digest: aggregates ponto + notes + kanban activity for one day and
 * persists the AI (or fallback) summary in {@code day_digest}.
 */
final class Diario {
    private final Db db;
    private final Object lock;
    private final Repo repo;
    private final Holidays calendar;

    Diario(Db db, Object lock, Repo repo, Holidays calendar) {
        this.db = db;
        this.lock = lock;
        this.repo = repo;
        this.calendar = calendar;
    }

    record Digest(String diaIso, String summary, String facts, String model, String crea, String upd) { }

    Map<String, Object> snapshot(LocalDate dia, int dailyMins, boolean falta, boolean normal) {
        synchronized (lock) {
            String iso = Str.iso(dia);
            List<Entry> entries = repo.on(dia);
            List<Map<String, Object>> notes = notesOn(iso);
            List<Map<String, Object>> cards = cardsOn(iso);
            List<Map<String, Object>> blocks = blocksOn(iso);
            List<Map<String, Object>> timeline = timeline(entries, notes, cards, blocks);
            List<Map<String, Object>> pairs = new ArrayList<>();
            long mins = 0;
            int i = 0;
            for (Entry e : entries) {
                long m = e.min();
                if (m > 0) {
                    mins += m;
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("label", (e.tin() == null ? "—" : Str.t(e.tin())) + "–" + (e.tout() == null ? "—" : Str.t(e.tout())));
                    p.put("mins", m);
                    p.put("index", ++i);
                    pairs.add(p);
                }
            }
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("ponto", entries.size());
            counts.put("notas", notes.size());
            counts.put("kanban", cards.size() + blocks.size());

            Map<String, Object> charts = new LinkedHashMap<>();
            charts.put("pairs", pairs);
            charts.put("counts", counts);
            charts.put("dayMins", mins);
            charts.put("dailyMins", dailyMins);

            List<Map<String, Object>> entryMaps = new ArrayList<>();
            for (Entry e : entries) entryMaps.add(entryMap(e, calendar.off(e.dia())));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("dia", Str.d(dia));
            out.put("diaIso", iso);
            out.put("d3", Str.d3(dia));
            out.put("holiday", calendar.off(dia));
            out.put("falta", falta);
            out.put("normal", normal);
            out.put("entries", entryMaps);
            out.put("notes", notes);
            out.put("kanbanCards", cards);
            out.put("kanbanBlocks", blocks);
            out.put("timeline", timeline);
            out.put("charts", charts);
            Digest dig = load(iso);
            out.put("digest", dig == null ? null : digestMap(dig));
            return out;
        }
    }

    /** Build Portuguese facts text used as AI input (and stored with the digest). */
    String factsText(Map<String, Object> snap, List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("Dia ").append(snap.get("dia")).append(" (").append(snap.get("d3")).append(")");
        Object holiday = snap.get("holiday");
        if (holiday != null && !holiday.toString().isBlank()) sb.append(" — ").append(holiday);
        if (Boolean.TRUE.equals(snap.get("falta"))) sb.append(" — falta");
        if (Boolean.TRUE.equals(snap.get("normal"))) sb.append(" — marcado normal");
        sb.append('\n');

        if (entries == null || entries.isEmpty()) sb.append("Batidas: nenhuma.\n");
        else {
            sb.append("Batidas:\n");
            for (Entry e : entries) {
                sb.append("- #").append(e.id()).append(' ')
                        .append(e.tin() == null ? "—" : Str.t(e.tin())).append('-')
                        .append(e.tout() == null ? "—" : Str.t(e.tout()));
                if (e.min() > 0) sb.append(" (").append(Dur.hm(e.min())).append(')');
                if (e.just() != null && !e.just().isBlank()) sb.append(" · ").append(clip(e.just(), 80));
                sb.append('\n');
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> charts = (Map<String, Object>) snap.get("charts");
        if (charts != null) {
            long dayMins = ((Number) charts.getOrDefault("dayMins", 0)).longValue();
            long daily = ((Number) charts.getOrDefault("dailyMins", 0)).longValue();
            sb.append("Total batido: ").append(Dur.hm(dayMins));
            if (daily > 0) sb.append(" (jornada ").append(Dur.hm(daily)).append(')');
            sb.append('\n');
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> notes = (List<Map<String, Object>>) snap.get("notes");
        if (notes == null || notes.isEmpty()) sb.append("Notas: nenhuma tocada.\n");
        else {
            sb.append("Notas tocadas:\n");
            for (Map<String, Object> n : notes) {
                sb.append("- #").append(n.get("id")).append(' ').append(n.getOrDefault("title", "(sem título)"));
                Object excerpt = n.get("excerpt");
                if (excerpt != null && !excerpt.toString().isBlank()) sb.append(" — ").append(excerpt);
                sb.append('\n');
            }
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cards = (List<Map<String, Object>>) snap.get("kanbanCards");
        if (cards == null || cards.isEmpty()) sb.append("Kanban: nenhum cartão tocado.\n");
        else {
            sb.append("Kanban (cartões):\n");
            for (Map<String, Object> c : cards) {
                sb.append("- #").append(c.get("id")).append(' ').append(c.getOrDefault("title", "(sem título)"));
                Object col = c.get("column");
                if (col != null && !col.toString().isBlank()) sb.append(" [").append(col).append(']');
                sb.append('\n');
            }
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) snap.get("kanbanBlocks");
        if (blocks != null && !blocks.isEmpty()) {
            sb.append("Kanban (bloqueios):\n");
            for (Map<String, Object> b : blocks) {
                sb.append("- #").append(b.get("id")).append(' ').append(b.getOrDefault("kindLabel", b.get("kind")));
                Object reason = b.get("reason");
                if (reason != null && !reason.toString().isBlank()) sb.append(" — ").append(clip(reason.toString(), 60));
                Object cleared = b.get("cleared");
                if (cleared != null && !cleared.toString().isBlank()) sb.append(" (liberado)");
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    Digest upsert(LocalDate dia, String summary, String facts, String model) {
        synchronized (lock) {
            String iso = Str.iso(dia);
            String sum = summary == null ? "" : summary.trim();
            String fac = facts == null ? "" : facts;
            String mod = model == null ? "" : model;
            try (PreparedStatement q = db.conn().prepareStatement(
                    "INSERT INTO day_digest(company_id, dia, summary, facts, model) VALUES(?,?,?,?,?) "
                            + "ON CONFLICT(company_id, dia) DO UPDATE SET "
                            + "summary=excluded.summary, facts=excluded.facts, model=excluded.model, "
                            + "upd=to_char(now(), 'YYYY-MM-DD HH24:MI')")) {
                q.setInt(1, db.company());
                q.setString(2, iso);
                q.setString(3, sum);
                q.setString(4, fac);
                q.setString(5, mod);
                q.executeUpdate();
            } catch (SQLException e) {
                throw new Problema("diário: " + Db.clean(e.getMessage()));
            }
            Digest d = load(iso);
            if (d == null) throw new Problema("diário: não gravou");
            return d;
        }
    }

    Digest load(String diaIso) {
        try (PreparedStatement q = db.conn().prepareStatement(
                "SELECT dia, summary, facts, model, crea, upd FROM day_digest WHERE company_id=? AND dia=?")) {
            q.setInt(1, db.company());
            q.setString(2, diaIso);
            try (ResultSet rs = q.executeQuery()) {
                if (!rs.next()) return null;
                return new Digest(
                        nz(rs.getString("dia")),
                        nz(rs.getString("summary")),
                        nz(rs.getString("facts")),
                        nz(rs.getString("model")),
                        nz(rs.getString("crea")),
                        nz(rs.getString("upd")));
            }
        } catch (SQLException e) {
            throw new Problema("diário: " + Db.clean(e.getMessage()));
        }
    }

    static Map<String, Object> digestMap(Digest d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("diaIso", d.diaIso());
        m.put("summary", d.summary());
        m.put("facts", d.facts());
        m.put("model", d.model());
        m.put("crea", d.crea());
        m.put("upd", d.upd());
        return m;
    }

    static Map<String, Object> entryMap(Entry e, String off) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("dia", Str.d(e.dia()));
        m.put("diaIso", Str.iso(e.dia()));
        m.put("d3", Str.d3(e.dia()));
        m.put("off", off == null ? "" : off);
        m.put("tin", Str.t(e.tin()));
        m.put("tout", Str.t(e.tout()));
        m.put("just", e.just());
        m.put("crea", e.crea());
        m.put("dur", e.dur());
        m.put("hours", e.min() < 0 ? "" : Dur.dec(e.min()));
        m.put("open", e.open());
        m.put("closed", e.closed());
        m.put("note", e.empty());
        return m;
    }

    /** Entries of a day for facts (caller already holds the lock). */
    List<Entry> entriesOf(LocalDate dia) { return repo.on(dia); }

    private List<Map<String, Object>> notesOn(String iso) {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = "SELECT id, title, body, crea, upd FROM note WHERE company_id=? "
                + "AND (crea LIKE ? OR upd LIKE ?) ORDER BY upd DESC, id DESC";
        try (PreparedStatement q = db.conn().prepareStatement(sql)) {
            q.setInt(1, db.company());
            q.setString(2, iso + "%");
            q.setString(3, iso + "%");
            try (ResultSet rs = q.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("title", nz(rs.getString("title")));
                    m.put("excerpt", clip(nz(rs.getString("body")), 120));
                    m.put("crea", nz(rs.getString("crea")));
                    m.put("upd", nz(rs.getString("upd")));
                    out.add(m);
                }
            }
        } catch (SQLException e) {
            throw new Problema("diário: " + Db.clean(e.getMessage()));
        }
        return out;
    }

    private List<Map<String, Object>> cardsOn(String iso) {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = "SELECT c.id, c.title, c.body, c.crea, c.upd, c.done, col.name AS column_name "
                + "FROM kanban_card c JOIN kanban_column col ON col.id=c.column_id AND col.company_id=c.company_id "
                + "WHERE c.company_id=? AND (c.crea LIKE ? OR c.upd LIKE ?) ORDER BY c.upd DESC, c.id DESC";
        try (PreparedStatement q = db.conn().prepareStatement(sql)) {
            q.setInt(1, db.company());
            q.setString(2, iso + "%");
            q.setString(3, iso + "%");
            try (ResultSet rs = q.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("title", nz(rs.getString("title")));
                    m.put("excerpt", clip(nz(rs.getString("body")), 80));
                    m.put("column", nz(rs.getString("column_name")));
                    m.put("done", nz(rs.getString("done")));
                    m.put("crea", nz(rs.getString("crea")));
                    m.put("upd", nz(rs.getString("upd")));
                    out.add(m);
                }
            }
        } catch (SQLException e) {
            throw new Problema("diário: " + Db.clean(e.getMessage()));
        }
        return out;
    }

    private List<Map<String, Object>> blocksOn(String iso) {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = "SELECT b.id, b.card_id, b.kind, b.reason, b.crea, b.cleared, c.title AS card_title "
                + "FROM kanban_block b JOIN kanban_card c ON c.id=b.card_id AND c.company_id=b.company_id "
                + "WHERE b.company_id=? AND (b.crea LIKE ? OR (b.cleared IS NOT NULL AND b.cleared LIKE ?)) "
                + "ORDER BY b.id DESC";
        try (PreparedStatement q = db.conn().prepareStatement(sql)) {
            q.setInt(1, db.company());
            q.setString(2, iso + "%");
            q.setString(3, iso + "%");
            try (ResultSet rs = q.executeQuery()) {
                while (rs.next()) {
                    String kind = nz(rs.getString("kind"));
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("cardId", rs.getInt("card_id"));
                    m.put("cardTitle", nz(rs.getString("card_title")));
                    m.put("kind", kind);
                    m.put("kindLabel", KanbanBlockKind.labelOf(kind));
                    m.put("reason", nz(rs.getString("reason")));
                    m.put("crea", nz(rs.getString("crea")));
                    m.put("cleared", nz(rs.getString("cleared")));
                    out.add(m);
                }
            }
        } catch (SQLException e) {
            throw new Problema("diário: " + Db.clean(e.getMessage()));
        }
        return out;
    }

    private List<Map<String, Object>> timeline(
            List<Entry> entries,
            List<Map<String, Object>> notes,
            List<Map<String, Object>> cards,
            List<Map<String, Object>> blocks) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Entry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "ponto");
            m.put("at", e.crea() == null || e.crea().isBlank()
                    ? Str.iso(e.dia()) + " " + (e.tin() == null ? "00:00" : Str.t(e.tin()))
                    : e.crea());
            m.put("id", e.id());
            m.put("title", (e.tin() == null ? "—" : Str.t(e.tin())) + "–" + (e.tout() == null ? "—" : Str.t(e.tout())));
            m.put("detail", e.just() == null ? "" : e.just());
            items.add(m);
        }
        for (Map<String, Object> n : notes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "nota");
            m.put("at", prefer(n.get("upd"), n.get("crea")));
            m.put("id", n.get("id"));
            m.put("title", n.getOrDefault("title", ""));
            m.put("detail", n.getOrDefault("excerpt", ""));
            items.add(m);
        }
        for (Map<String, Object> c : cards) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "kanban");
            m.put("at", prefer(c.get("upd"), c.get("crea")));
            m.put("id", c.get("id"));
            m.put("title", c.getOrDefault("title", ""));
            Object col = c.get("column");
            m.put("detail", col == null ? "" : col.toString());
            items.add(m);
        }
        for (Map<String, Object> b : blocks) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "bloqueio");
            String cleared = b.get("cleared") == null ? "" : b.get("cleared").toString();
            m.put("at", cleared.isBlank() ? prefer(b.get("crea"), "") : cleared);
            m.put("id", b.get("id"));
            m.put("title", b.getOrDefault("kindLabel", b.get("kind")));
            m.put("detail", b.getOrDefault("cardTitle", ""));
            items.add(m);
        }
        items.sort((a, b) -> String.valueOf(b.get("at")).compareTo(String.valueOf(a.get("at"))));
        return items;
    }

    private static String prefer(Object a, Object b) {
        String s = a == null ? "" : a.toString();
        if (!s.isBlank()) return s;
        return b == null ? "" : b.toString();
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.trim().replace('\r', ' ').replace('\n', ' ');
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
