package ponto.view;

import ponto.model.Dur;
import ponto.model.Entry;
import ponto.model.Folder;
import ponto.model.Note;
import ponto.model.Report;
import ponto.model.Str;

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
        m.put("folderId", n.folderId());
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
}
