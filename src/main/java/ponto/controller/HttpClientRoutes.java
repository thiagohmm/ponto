package ponto.controller;

import ponto.app.Service;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.Map;
import java.util.function.Function;

/** Authenticated GET/PUT of the shared ponto-http collection (DB-backed). */
final class HttpClientRoutes {
    private final Function<Context, Service> service;

    HttpClientRoutes(Function<Context, Service> service) {
        this.service = service;
    }

    void register(Javalin app) {
        app.get("/api/http-client/collection", this::get);
        app.put("/api/http-client/collection", this::put);
    }

    private void get(Context ctx) {
        ctx.json(service.apply(ctx).httpClientCollection());
    }

    private void put(Context ctx) {
        String raw = ctx.body();
        ctx.json(service.apply(ctx).saveHttpClientCollection(raw));
    }
}
