package ponto.view;

import ponto.model.Company;
import ponto.model.Dur;
import ponto.model.Entry;
import ponto.model.Holidays;
import ponto.model.Folder;
import ponto.model.KanbanBlock;
import ponto.model.KanbanCard;
import ponto.model.KanbanCardFile;
import ponto.model.KanbanColumn;
import ponto.model.Note;
import ponto.model.NoteFile;
import ponto.model.Report;
import ponto.model.Service;
import ponto.model.Str;
import ponto.model.Tag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The JSON views of one record and of one day summary: those keys are the
 * contract of web/src/types.ts and may not change.
 */
public final class Json {

    public static Map<String, Object> json(Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("dia", Str.d(e.dia()));
        m.put("diaIso", Str.iso(e.dia()));
        m.put("d3", Str.d3(e.dia()));
        m.put("off", new Holidays(java.util.List::of).off(e.dia()));
        m.put("tin", Str.t(e.tin()));
        m.put("tout", Str.t(e.tout()));
        m.put("just", e.just());
        m.put("crea", e.crea());
        m.put("dur", Cols.dur(e));
        m.put("hours", Cols.hours(e));
        m.put("open", e.open());
        m.put("closed", e.closed());
        m.put("note", e.empty());
        return m;
    }

    public static Map<String, Object> json(Note n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.id());
        m.put("title", n.title());
        m.put("body", n.body());
        m.put("crea", n.crea());
        m.put("upd", n.upd());
        m.put("revision", n.revision());
        m.put("folderId", n.folderId());
        m.put("tagIds", n.tagIds() == null ? java.util.List.of() : n.tagIds());
        return m;
    }

    public static Map<String, Object> json(NoteFile f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.id());
        m.put("noteId", f.noteId());
        m.put("name", f.name());
        m.put("size", f.size());
        m.put("ctype", f.ctype());
        m.put("crea", f.crea());
        return m;
    }

    public static Map<String, Object> json(KanbanCardFile f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.id());
        m.put("cardId", f.cardId());
        m.put("name", f.name());
        m.put("size", f.size());
        m.put("ctype", f.ctype());
        m.put("crea", f.crea());
        return m;
    }

    public static Map<String, Object> json(Tag t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("name", t.name());
        m.put("color", t.color());
        m.put("crea", t.crea());
        return m;
    }

    public static Map<String, Object> json(Company c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("name", c.name());
        m.put("off", c.off());
        m.put("daily", c.daily());
        m.put("crea", c.crea());
        return m;
    }

    public static Map<String, Object> json(Folder f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.id());
        m.put("parent", f.parent());
        m.put("name", f.name());
        m.put("crea", f.crea());
        return m;
    }

    public static Map<String, Object> json(KanbanColumn c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("name", c.name());
        m.put("position", c.position());
        m.put("crea", c.crea());
        return m;
    }

    public static Map<String, Object> json(KanbanCard c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("columnId", c.columnId());
        m.put("title", c.title());
        m.put("body", c.body());
        m.put("done", c.done());
        m.put("position", c.position());
        m.put("revision", c.revision());
        m.put("crea", c.crea());
        m.put("upd", c.upd());
        m.put("blocked", c.blocked());
        m.put("blocks", c.blocks().stream().map(Json::json).toList());
        return m;
    }

    public static Map<String, Object> json(KanbanBlock b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.id());
        m.put("cardId", b.cardId());
        m.put("kind", b.kind());
        m.put("kindLabel", b.kindLabel());
        m.put("reason", b.reason());
        m.put("crea", b.crea());
        m.put("cleared", b.cleared());
        return m;
    }

    public static Map<String, Object> jsonDay(Report.Day d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dia", Str.d(d.dia()));
        m.put("diaIso", Str.iso(d.dia()));
        m.put("d3", d.d3());
        m.put("lines", d.lines());
        m.put("mins", d.mins());
        m.put("dur", Dur.hm(d.mins()));
        m.put("hours", d.dec());
        return m;
    }

    /** The month bank: saldo, jornada, days left and the plan (Web adds days + months). */
    public static Map<String, Object> jsonBank(Service.Bank b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ym", b.ym().toString());
        m.put("saldo", b.saldoLabel());
        m.put("saldoMins", b.saldoMins());
        m.put("dailyMins", b.dailyMins());
        m.put("remaining", b.remaining());
        m.put("planMins", b.planMins());
        m.put("planLabel", b.planLabel());
        m.put("neg", b.neg());
        return m;
    }

    public static Map<String, Object> jsonBankDay(Service.BankDay d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dia", Str.d(d.dia()));
        m.put("diaIso", Str.iso(d.dia()));
        m.put("d3", Str.d3(d.dia()));
        m.put("mins", d.mins());
        m.put("dur", Dur.hm(d.mins()));
        m.put("hours", Str.n(d.mins() / 60.0));
        m.put("falta", d.falta());
        m.put("normal", d.normal());
        m.put("kind", d.kind());
        return m;
    }

    public static Map<String, Object> jsonMonthSaldo(Service.MonthSaldo m0) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ym", m0.ym().toString());
        m.put("saldo", Dur.signed(m0.saldoMins()));
        m.put("saldoMins", m0.saldoMins());
        return m;
    }
}
