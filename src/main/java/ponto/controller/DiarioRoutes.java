package ponto.controller;

import ponto.Bad;
import ponto.model.Assist;
import ponto.model.Service;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.time.LocalDate;
import java.util.Map;
import java.util.function.Function;

/** Day digest page: timeline + charts + AI summary persisted in day_digest. */
final class DiarioRoutes {
    private final Function<Context, Service> service;
    private final Assist assist;

    DiarioRoutes(Function<Context, Service> service, Assist assist) {
        this.service = service;
        this.assist = assist;
    }

    void register(Javalin app) {
        app.get("/api/diario", this::get);
        app.post("/api/diario/resumo", this::resumo);
    }

    private void get(Context ctx) {
        Service svc = service.apply(ctx);
        LocalDate dia = Service.parseDate(ctx.queryParam("dia"), ponto.model.Time.today());
        ctx.json(svc.diario(dia));
    }

    private void resumo(Context ctx) {
        Service svc = service.apply(ctx);
        ResumoReq r = ctx.bodyAsClass(ResumoReq.class);
        LocalDate dia = Service.parseDate(r == null ? null : r.dia(), ponto.model.Time.today());
        if (dia == null) throw new Bad("data obrigatória");
        Map<String, Object> dig = svc.gerarResumoDiario(dia, assist);
        ctx.status(HttpStatus.OK).json(dig);
    }

    record ResumoReq(String dia) { }
}
