package ponto.controller;

import ponto.Bad;
import ponto.model.KanbanCard;
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
        svc.kanban().deleteColumn(id);
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
        ctx.json(Json.json(svc.kanban().updateCard(id, nz(r.title()), nz(r.body()), r.revision())));
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
        svc.kanban().deleteCard(id);
        ctx.json(Map.of("ok", true, "id", id));
    }

    private static String nz(String s) { return s == null ? "" : s; }

    public record ColumnReq(String name, Integer position) { }
    public record CardReq(String title, String body, Integer columnId, Integer position, Integer revision) { }
    public record BlockReq(String kind, String reason) { }
}
