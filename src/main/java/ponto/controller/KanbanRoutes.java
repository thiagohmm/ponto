package ponto.controller;

import ponto.Bad;
import ponto.Problema;
import ponto.model.KanbanCard;
import ponto.model.KanbanCardFile;
import ponto.model.KanbanColumn;
import ponto.model.Service;
import ponto.view.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** HTTP adapter for the per-company kanban board. */
final class KanbanRoutes {
    private final Function<Context, Service> service;
    KanbanRoutes(Function<Context, Service> service) { this.service = service; }

    void register(Javalin app) {
        app.get("/api/kanban", this::board);
        app.post("/api/kanban/columns", this::columnCreate);
        app.put("/api/kanban/columns/{id}", this::columnUpdate);
        app.delete("/api/kanban/columns/{id}", this::columnDelete);
        app.post("/api/kanban/cards", this::cardCreate);
        app.put("/api/kanban/cards/{id}", this::cardUpdate);
        app.put("/api/kanban/cards/{id}/move", this::cardMove);
        app.post("/api/kanban/cards/{id}/block", this::cardBlock);
        app.post("/api/kanban/cards/{id}/unblock", this::cardUnblock);
        app.delete("/api/kanban/cards/{id}", this::cardDelete);
        app.get("/api/kanban/cards/{id}/files", this::filesList);
        app.post("/api/kanban/cards/{id}/files", this::fileUpload);
        app.get("/api/kanban/cards/{id}/files/{fid}/download", this::fileDownload);
        app.delete("/api/kanban/cards/{id}/files/{fid}", this::fileDelete);
    }

    private void board(Context ctx) {
        Service svc = service.apply(ctx);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", svc.kanban().columns().stream().map(Json::json).toList());
        out.put("cards", svc.kanban().cards().stream().map(Json::json).toList());
        ctx.json(out);
    }

    private void columnCreate(Context ctx) {
        Service svc = service.apply(ctx);
        ColumnReq r = ctx.bodyAsClass(ColumnReq.class);
        KanbanColumn c = svc.kanban().createColumn(nz(r.name()));
        ctx.status(HttpStatus.CREATED).json(Json.json(c));
    }

    private void columnUpdate(Context ctx) {
        Service svc = service.apply(ctx);
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        ColumnReq r = ctx.bodyAsClass(ColumnReq.class);
        ctx.json(Json.json(svc.kanban().updateColumn(id, r.name(), r.position())));
    }

    private void columnDelete(Context ctx) {
        Service svc = service.apply(ctx);
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        svc.deleteKanbanColumn(id);
        ctx.json(Map.of("ok", true, "id", id));
    }

    private void cardCreate(Context ctx) {
        Service svc = service.apply(ctx);
        CardReq r = ctx.bodyAsClass(CardReq.class);
        if (r.columnId() == null || r.columnId() < 1) throw new Bad("columnId obrigatório");
        KanbanCard c = svc.kanban().createCard(nz(r.title()), nz(r.body()), r.columnId());
        ctx.status(HttpStatus.CREATED).json(Json.json(c));
    }

    private void cardUpdate(Context ctx) {
        Service svc = service.apply(ctx);
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        CardReq r = ctx.bodyAsClass(CardReq.class);
        if (r.revision() == null || r.revision() < 1) throw new Bad("revision obrigatória");
        ctx.json(Json.json(svc.kanban().updateCard(id, nz(r.title()), nz(r.body()), nz(r.done()), r.revision())));
    }

    private void cardMove(Context ctx) {
        Service svc = service.apply(ctx);
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        CardReq r = ctx.bodyAsClass(CardReq.class);
        if (r.columnId() == null || r.columnId() < 1) throw new Bad("columnId obrigatório");
        if (r.position() == null || r.position() < 0) throw new Bad("position obrigatória");
        ctx.json(Json.json(svc.kanban().moveCard(id, r.columnId(), r.position())));
    }

    private void cardBlock(Context ctx) {
        Service svc = service.apply(ctx);
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        BlockReq r = ctx.bodyAsClass(BlockReq.class);
        ctx.json(Json.json(svc.kanban().blockCard(id, r.kind(), r.reason())));
    }

    private void cardUnblock(Context ctx) {
        Service svc = service.apply(ctx);
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        ctx.json(Json.json(svc.kanban().unblockCard(id)));
    }

    private void cardDelete(Context ctx) {
        Service svc = service.apply(ctx);
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        svc.deleteKanbanCard(id);
        ctx.json(Map.of("ok", true, "id", id));
    }

    private void filesList(Context ctx) {
        Service svc = service.apply(ctx);
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        svc.kanban().byId(id);
        ctx.json(Map.of("files", svc.files().listForCard(id).stream().map(f -> fileJson(svc, f)).toList()));
    }

    private void fileUpload(Context ctx) {
        Service svc = service.apply(ctx);
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        svc.kanban().byId(id);
        var up = ctx.uploadedFile("file");
        if (up == null || up.filename() == null || up.filename().isBlank())
            throw new Bad("arquivo: envie o campo multipart 'file'");
        if (up.size() > ponto.model.Files.maxUpload()) throw new io.javalin.http.HttpResponseException(413, "Arquivo maior que o limite de upload.");
        if (up.size() == 0) throw new Bad("arquivo: arquivo vazio");
        KanbanCardFile f;
        try {
            f = svc.files().addForCard(id, up.filename(), up.contentType(), up.content().readNBytes((int) ponto.model.Files.maxUpload() + 1));
        } catch (java.io.IOException e) {
            throw new Problema("arquivo: " + e.getMessage());
        }
        ctx.status(HttpStatus.CREATED).json(fileJson(svc, f));
    }

    private void fileDownload(Context ctx) {
        Service svc = service.apply(ctx);
        int cardId = ctx.pathParamAsClass("id", Integer.class).get();
        int fid = ctx.pathParamAsClass("fid", Integer.class).get();
        svc.kanban().byId(cardId);
        KanbanCardFile f = svc.files().mustCardFile(fid);
        if (f.cardId() != cardId) throw new Bad("o arquivo " + fid + " não está no cartão " + cardId);
        byte[] data = svc.files().downloadCardFile(fid);
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
        int cardId = ctx.pathParamAsClass("id", Integer.class).get();
        int fid = ctx.pathParamAsClass("fid", Integer.class).get();
        svc.kanban().byId(cardId);
        KanbanCardFile f = svc.files().mustCardFile(fid);
        if (f.cardId() != cardId) throw new Bad("o arquivo " + fid + " não está no cartão " + cardId);
        svc.files().removeCardFile(fid);
        ctx.json(Map.of("ok", true, "id", fid));
    }

    private static Map<String, Object> fileJson(Service svc, KanbanCardFile f) {
        var json = Json.json(f);
        json.put("state", svc.files().cardState(f.id()));
        return json;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    public record ColumnReq(String name, Integer position) { }
    public record CardReq(String title, String body, String done, Integer columnId, Integer position, Integer revision) { }
    public record BlockReq(String kind, String reason) { }
}
