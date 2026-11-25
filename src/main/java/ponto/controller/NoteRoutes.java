package ponto.controller;

import ponto.app.Service;

import ponto.Bad;
import ponto.Problema;
import ponto.model.*;
import ponto.view.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Function;

/** HTTP adapter for notes, folders, tags and attachments. */
final class NoteRoutes {
    private final Function<Context, Service> service;
    NoteRoutes(Function<Context, Service> service) { this.service = service; }
    void register(Javalin app) {
        app.get("/api/notes", this::notesList);
        app.get("/api/notes/{id}", this::noteOne);
        app.post("/api/notes", this::noteCreate);
        app.put("/api/notes/{id}", this::noteUpdate);
        app.put("/api/notes/{id}/folder", this::noteMove);
        app.delete("/api/notes/{id}", this::noteDelete);
        app.put("/api/notes/{id}/tags", this::noteTags);
        app.get("/api/notes/{id}/files", this::filesList);
        app.post("/api/notes/{id}/files", this::fileUpload);
        app.get("/api/notes/{id}/files/{fid}/download", this::fileDownload);
        app.delete("/api/notes/{id}/files/{fid}", this::fileDelete);
        app.post("/api/folders", this::folderCreate);
        app.put("/api/folders/{id}", this::folderUpdate);
        app.delete("/api/folders/{id}", this::folderDelete);
        app.post("/api/tags", this::tagCreate);
        app.put("/api/tags/{id}", this::tagUpdate);
        app.delete("/api/tags/{id}", this::tagDelete);
    }

    private void notesList(Context ctx) {
        Service svc = service.apply(ctx);
        String q = ctx.queryParam("q");
        List<Note> rows = svc.listNotes(q);
        Map<Integer, List<String>> fileNames = svc.noteFileNames(rows.stream().map(Note::id).toList());
        List<Map<String, Object>> notes = rows.stream().map(n -> {
            Map<String, Object> json = Json.json(n);
            json.put("fileNames", fileNames.getOrDefault(n.id(), List.of()));
            return json;
        }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", rows.size());
        out.put("notes", notes);
        out.put("folders", svc.folders().stream().map(Json::json).toList());
        out.put("tags", svc.tags().stream().map(Json::json).toList());
        ctx.json(out);
    }

    private void noteOne(Context ctx) {
        Service svc = service.apply(ctx);
        ctx.json(Json.json(svc.noteById(ctx.pathParamAsClass("id", Integer.class).get())));
    }

    private void noteCreate(Context ctx) {
        Service svc = service.apply(ctx);
        NoteReq r = ctx.bodyAsClass(NoteReq.class);
        Note n = svc.createNote(nz(r.title()), nz(r.body()), r.folderId(), r.tagIds());
        ctx.status(HttpStatus.CREATED).json(Json.json(n));
    }

    private void noteUpdate(Context ctx) {
        Service svc = service.apply(ctx);
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        NoteReq r = ctx.bodyAsClass(NoteReq.class);
        if (r.revision() == null || r.revision() < 1) throw new Bad("revision obrigatória");
        ctx.json(Json.json(svc.updateNote(id, nz(r.title()), nz(r.body()), r.revision())));
    }

    private void noteMove(Context ctx) {
        Service svc = service.apply(ctx);
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        NoteReq r = ctx.bodyAsClass(NoteReq.class);
        ctx.json(Json.json(svc.moveNote(id, r.folderId())));
    }

    private void folderCreate(Context ctx) {
        Service svc = service.apply(ctx);
        FolderReq r = ctx.bodyAsClass(FolderReq.class);
        ctx.status(HttpStatus.CREATED).json(Json.json(svc.createFolder(nz(r.name()), r.parent())));
    }

    private void folderUpdate(Context ctx) {
        Service svc = service.apply(ctx);
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        FolderReq r = ctx.bodyAsClass(FolderReq.class);
        ctx.json(Json.json(svc.transaction(() -> {
            if (r.name() != null) svc.renameFolder(id, r.name());
            return svc.moveFolder(id, r.parent());
        })));
    }

    private void folderDelete(Context ctx) {
        Service svc = service.apply(ctx);
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        svc.deleteFolder(id);
        ctx.json(Map.of("ok", true, "id", id));
    }

    private void noteTags(Context ctx) {
        Service svc = service.apply(ctx);
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        NoteReq r = ctx.bodyAsClass(NoteReq.class);
        if (r.tagId() != null)
            ctx.json(Json.json(svc.addNoteTag(id, r.tagId())));
        else
            ctx.json(Json.json(svc.setNoteTags(id, r.tagIds())));
    }

    private void tagCreate(Context ctx) {
        Service svc = service.apply(ctx);
        TagReq r = ctx.bodyAsClass(TagReq.class);
        ctx.status(HttpStatus.CREATED).json(Json.json(svc.createTag(nz(r.name()), r.color())));
    }

    private void tagUpdate(Context ctx) {
        Service svc = service.apply(ctx);
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        TagReq r = ctx.bodyAsClass(TagReq.class);
        ctx.json(Json.json(svc.renameTag(id, r.name(), r.color())));
    }

    private void tagDelete(Context ctx) {
        Service svc = service.apply(ctx);
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        svc.deleteTag(id);
        ctx.json(Map.of("ok", true, "id", id));
    }

    private void noteDelete(Context ctx) {
        Service svc = service.apply(ctx);
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        svc.deleteNote(id);
        ctx.json(Map.of("ok", true, "id", id));
    }

    private void filesList(Context ctx) {
        Service svc = service.apply(ctx);
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        svc.noteById(id);
        Map<Integer, String> states = svc.noteFileStates(id);
        ctx.json(Map.of("files", svc.listNoteFiles(id).stream().map(f -> {
            var json = Json.json(f);
            json.put("state", states.getOrDefault(f.id(), "ready"));
            return json;
        }).toList()));
    }

    private void fileUpload(Context ctx) {
        Service svc = service.apply(ctx);
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        svc.noteById(id);
        var up = ctx.uploadedFile("file");
        if (up == null || up.filename() == null || up.filename().isBlank())
            throw new Bad("arquivo: envie o campo multipart 'file'");
        if (up.size() > Service.maxUpload()) throw new io.javalin.http.HttpResponseException(413, "Arquivo maior que o limite de upload.");
        if (up.size() == 0) throw new Bad("arquivo: arquivo vazio");
        NoteFile f;
        try {
            f = svc.addNoteFile(id, up.filename(), up.contentType(), up.content().readNBytes((int) Service.maxUpload() + 1));
        } catch (java.io.IOException e) {
            throw new Problema("arquivo: " + e.getMessage());
        }
        ctx.status(HttpStatus.CREATED).json(fileJson(svc, f));
    }

    private void fileDownload(Context ctx) {
        Service svc = service.apply(ctx);
        int noteId = ctx.pathParamAsClass("id", Integer.class).get();
        int fid = ctx.pathParamAsClass("fid", Integer.class).get();
        svc.noteById(noteId);
        NoteFile f = svc.mustNoteFile(fid);
        if (f.noteId() != noteId) throw new Bad("o arquivo " + fid + " não está na nota " + noteId);
        byte[] data = svc.downloadNoteFile(fid);
        String name = f.name().replace("\"", "");
        String enc = java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        StringBuilder disp = new StringBuilder("attachment");
        if (!name.equals(enc)) {
            disp.append("; filename=\"")
                .append(name.replaceAll("[^\\u0020-\\u007E]", "_"))
                .append("\"; filename*=UTF-8''").append(enc);
        } else {
            disp.append("; filename=\"").append(name).append("\"");
        }
        ctx.contentType(f.ctype().isBlank() ? "application/octet-stream" : f.ctype());
        ctx.header("Content-Disposition", disp.toString());
        ctx.result(data);
    }

    private void fileDelete(Context ctx) {
        Service svc = service.apply(ctx);
        int noteId = ctx.pathParamAsClass("id", Integer.class).get();
        int fid = ctx.pathParamAsClass("fid", Integer.class).get();
        svc.noteById(noteId);
        NoteFile f = svc.mustNoteFile(fid);
        if (f.noteId() != noteId) throw new Bad("o arquivo " + fid + " não está na nota " + noteId);
        svc.removeNoteFile(fid);
        ctx.json(Map.of("ok", true, "id", fid));
    }

    private static Map<String,Object> fileJson(Service svc, NoteFile f) {
        var json = Json.json(f);
        json.put("state", svc.noteFileState(f.id()));
        return json;
    }
    private static String nz(String s) { return s == null ? "" : s; }
    record NoteReq(String title, String body, Integer folderId, Integer tagId, List<Integer> tagIds, Integer revision) { }
    record FolderReq(String name, Integer parent) { }
    record TagReq(String name, String color) { }
}
