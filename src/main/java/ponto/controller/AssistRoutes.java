package ponto.controller;

import ponto.model.Assist;
import ponto.model.JustCategory;

import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Optional Ollama suggestions — never mutates notes or ponto rows. */
final class AssistRoutes {
    private final Assist assist;

    AssistRoutes(Assist assist) { this.assist = assist; }

    void register(Javalin app) {
        app.get("/api/assist/status", this::status);
        app.post("/api/assist/note-tags", this::noteTags);
        app.post("/api/assist/just", this::just);
    }

    private void status(Context ctx) {
        ctx.json(assist.status());
    }

    private void noteTags(Context ctx) {
        NoteTagsReq r = ctx.bodyAsClass(NoteTagsReq.class);
        List<Assist.TagRef> tags = new ArrayList<>();
        if (r.tags() != null) {
            for (TagIn t : r.tags()) {
                if (t == null || t.id() == null || t.name() == null) continue;
                tags.add(new Assist.TagRef(t.id(), t.name()));
            }
        }
        List<Integer> ids = assist.suggestNoteTags(nz(r.title()), nz(r.body()), tags);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tagIds", ids);
        ctx.json(out);
    }

    private void just(Context ctx) {
        JustReq r = ctx.bodyAsClass(JustReq.class);
        Assist.JustSuggestion s = assist.suggestJust(nz(r.text()));
        // ensure category is always in taxonomy
        String cat = JustCategory.ofCode(s.category()).map(JustCategory::code).orElse("outro");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("category", cat);
        out.put("phrases", s.phrases() == null ? List.of() : s.phrases());
        ctx.json(out);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    record TagIn(Integer id, String name) { }
    record NoteTagsReq(String title, String body, List<TagIn> tags) { }
    record JustReq(String text) { }
}
