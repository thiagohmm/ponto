package ponto.controller;

import ponto.Bad;
import ponto.NeedConfirm;
import ponto.Problema;
import ponto.Ver;
import ponto.model.Company;
import ponto.model.Entry;
import ponto.model.Report;
import ponto.model.Service;
import ponto.model.Str;
import ponto.view.Csv;
import ponto.view.Json;
import ponto.view.Json3;
import ponto.view.Pdf;
import ponto.view.Xls;
import ponto.model.Period;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.staticfiles.Location;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HTTP API + static React build. Same Service as the terminal menu. */
public final class Web {

    private final Service svc;
    private final int port;

    public Web(Service svc, int port) { this.svc = svc; this.port = port; }

    public Javalin application() {
        Javalin app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.http.maxRequestSize = ponto.model.Files.maxUpload() + 1024 * 1024;
            cfg.jsonMapper(new Json3());
            if (Web.class.getResource("/public/index.html") != null) {
                cfg.staticFiles.add(s -> {
                    s.hostedPath = "/";
                    s.directory = "/public";
                    s.location = Location.CLASSPATH;
                    s.mimeTypes.add("application/manifest+json", "webmanifest");
                });
                cfg.spaRoot.addFile("/", "/public/index.html", Location.CLASSPATH);
            }
        });

        app.before(ctx -> {
            ctx.attribute("requestId", java.util.UUID.randomUUID().toString());
            ctx.header("X-Request-ID", ctx.attribute("requestId"));
            ctx.header("X-Content-Type-Options", "nosniff");
            if (!ctx.path().startsWith("/api/")) return;
            ctx.header("Cache-Control", "no-store");
            if (ctx.path().equals("/api/health")) return;
            String raw = ctx.queryParam("companyId");
            boolean catalog = ctx.path().equals("/api/companies") && ctx.method() == io.javalin.http.HandlerType.GET;
            if ((raw == null || raw.isBlank()) && catalog) { ctx.attribute("service", svc); return; }
            if (raw == null || raw.isBlank()) throw new Bad("Selecione uma empresa e atualize a página (companyId obrigatório).");
            int id;
            try { id = Integer.parseInt(raw); if (id < 1) throw new NumberFormatException(); }
            catch (NumberFormatException e) { throw new Bad("companyId inválido"); }
            ctx.attribute("service", svc.forCompany(id));
        });
        app.after(ctx -> {
            String path = ctx.path();
            if (!path.startsWith("/assets")) ctx.header("Cache-Control", "no-store");
        });
        app.exception(NeedConfirm.class, (e, ctx) -> ctx.status(409).json(Map.of(
                "need", e.code, "message", e.getMessage(), "requestId", requestId(ctx))));
        app.exception(ponto.Conflict.class, (e, ctx) -> error(ctx, 409, e.getMessage()));
        app.exception(ponto.NotFound.class, (e, ctx) -> error(ctx, 404, e.getMessage()));
        app.exception(ponto.StorageFailure.class, (e, ctx) -> error(ctx, 503, e.getMessage()));
        app.exception(Bad.class, (e, ctx) -> error(ctx, 400, e.getMessage()));
        app.exception(io.javalin.validation.ValidationException.class, (e, ctx) -> error(ctx, 400, "Requisição inválida."));
        app.exception(io.javalin.http.HttpResponseException.class, (e, ctx) -> error(ctx, e.getStatus(), e.getMessage()));
        app.exception(Problema.class, (e, ctx) -> {
            System.err.println("request=" + requestId(ctx) + " " + e.getMessage());
            error(ctx, 400, "Não foi possível concluir a operação.");
        });
        app.exception(Exception.class, (e, ctx) -> {
            System.err.println("request=" + requestId(ctx) + " " + ctx.method() + " " + ctx.path());
            e.printStackTrace(System.err);
            error(ctx, 500, "Erro interno. Tente novamente e informe o identificador da requisição se persistir.");
        });
        app.get("/api/health", ctx -> {
            boolean db = svc.healthy();
            ctx.status(db ? 200 : 503).json(Map.of("status", db ? "ok" : "error", "database", db,
                    "storage", !svc.files().configured() ? "disabled" : svc.files().storageUp() ? "ok" : "unavailable",
                    "pendingObjects", svc.files().pending(), "version", Ver.N));
        });
        app.post("/api/storage/retry", ctx -> { service(ctx).files().retry(); ctx.json(Map.of("ok", true)); });

        app.get("/api/home", ctx -> ctx.json(service(ctx).home()));
        app.get("/api/entries", this::list);
        app.get("/api/entries/{id}", this::one);
        app.post("/api/entries", this::create);
        app.put("/api/entries/{id}", this::update);
        app.delete("/api/entries/{id}", this::delete);
        app.delete("/api/days/{dia}", this::deleteDay);
        app.put("/api/days/{dia}/falta", this::falta);
        app.put("/api/days/{dia}/normal", this::normal);
        app.get("/api/day", this::day);
        app.get("/api/holiday", this::holiday);
        app.get("/api/bank", this::bank);
        app.get("/api/sum", this::sum);
        app.get("/api/export/{kind}", this::export);
        app.get("/api/config", ctx -> ctx.json(service(ctx).config()));
        app.put("/api/config", this::config);
        app.get("/api/companies", this::companies);
        app.post("/api/companies", this::companyCreate);
        app.put("/api/companies/active", this::companyActive);
        app.put("/api/companies/{id}", this::companyUpdate);
        app.delete("/api/companies/{id}", this::companyDelete);
        new NoteRoutes(this::service).register(app);
        new KanbanRoutes(this::service).register(app);
        app.events(events -> events.serverStopped(svc::closeResources));
        return app;
    }

    public void start() {
        svc.files().start();
        Javalin app = application();
        app.start("0.0.0.0", port);
        System.out.println("ponto web  http://0.0.0.0:" + port + "   db " + svc.dbPath());
        var stopped = new java.util.concurrent.CountDownLatch(1);
        Thread hook = new Thread(() -> { app.stop(); stopped.countDown(); }, "ponto-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try { stopped.await(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally { app.stop(); try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) { } }
    }

    private Service service(Context ctx) {
        Service selected = ctx.attribute("service");
        if (selected == null) throw new Bad("empresa obrigatória");
        return selected;
    }

    private static String requestId(Context ctx) {
        String id = ctx.attribute("requestId");
        return id == null ? "unknown" : id;
    }

    private static void error(Context ctx, int status, String message) {
        ctx.status(status).json(Map.of("error", message == null ? "Requisição inválida." : message, "requestId", requestId(ctx)));
    }

    private static Map<String, Object> entryJson(Service svc, Entry entry) {
        var json = Json.json(entry);
        json.put("off", svc.holiday(entry.dia()));
        return json;
    }

    private void list(Context ctx) {
        Service svc = service(ctx);
        String q = ctx.queryParam("q");
        String dia = ctx.queryParam("dia");
        String fromRaw = ctx.queryParam("from");
        String toRaw = ctx.queryParam("to");
        int n = Math.min(1000, Math.max(1, intParam(ctx, "n", 40)));
        int skip = intParam(ctx, "skip", 0);
        boolean range = !isBlank(fromRaw) && !isBlank(toRaw);
        List<Entry> rows;
        Service.Totals tot;
        long total;
        long ot;
        if (range) {
            LocalDate from = Service.parseDate(fromRaw, null);
            LocalDate to = Service.parseDate(toRaw, null);
            if (from == null || to == null) throw new Bad("from/to: use uma data como 2026-09-01");
            if (to.isBefore(from)) { LocalDate x = from; from = to; to = x; }
            if (!isBlank(q)) {
                var result = svc.filteredRange(from, to, q, n, skip);
                rows = result.entries(); tot = result.totals(); total = result.total(); ot = result.overtime();
            } else {
            rows = svc.listBetween(from, to, n, skip);
            tot = svc.totalsBetween(from, to);
            total = svc.countBetween(from, to);
            ot = svc.overtimeBetween(from, to);
            }
        } else {
            rows = svc.list(q, dia, n, skip);
            tot = svc.listTotals(q, dia);
            total = svc.count(q, dia);
            ot = svc.overtime(q, dia);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("shown", rows.size());
        out.put("skip", skip);
        out.put("mins", tot.mins());
        out.put("dur", tot.dur());
        out.put("hours", tot.hours());
        out.put("open", tot.open());
        out.put("overtime", ot);
        out.put("faltas", svc.faltas().stream().map(Str::iso).toList());
        out.put("normais", svc.normais().stream().map(Str::iso).toList());
        out.put("entries", rows.stream().map(e -> entryJson(svc, e)).toList());
        ctx.json(out);
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private void one(Context ctx) {
        Service svc = service(ctx);
        ctx.json(entryJson(svc, svc.byId(ctx.pathParamAsClass("id", Integer.class).get())));
    }

    private void create(Context ctx) {
        Service svc = service(ctx);
        EntryReq r = ctx.bodyAsClass(EntryReq.class);
        LocalDate dia = Service.parseDate(r.dia(), ponto.model.Time.today());
        LocalTime tin = Service.parseTime(r.tin());
        LocalTime tout = Service.parseTime(r.tout());
        Entry e = svc.create(dia, tin, tout, nz(r.just()), r.flag(r.closeOpen()),
                r.flag(r.confirmMidnight()), r.flag(r.confirmOverlap()));
        ctx.status(HttpStatus.CREATED).json(entryJson(svc, e));
    }

    private void update(Context ctx) {
        Service svc = service(ctx);
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        EntryReq r = ctx.bodyAsClass(EntryReq.class);
        Entry cur = svc.byId(id);
        LocalDate dia = Service.parseDate(r.dia(), cur.dia());
        LocalTime tin = r.tin() == null ? cur.tin() : Service.parseTime(r.tin());
        LocalTime tout = r.tout() == null ? cur.tout() : Service.parseTime(r.tout());
        String just = r.just() == null ? cur.just() : r.just();
        Entry e = svc.update(id, dia, tin, tout, just, r.flag(r.confirmMidnight()), r.flag(r.confirmOverlap()));
        ctx.json(entryJson(svc, e));
    }

    private void delete(Context ctx) {
        Service svc = service(ctx);
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        svc.delete(id);
        ctx.json(Map.of("ok", true, "id", id));
    }

    private void deleteDay(Context ctx) {
        Service svc = service(ctx);
        LocalDate d = Service.parseDate(ctx.pathParam("dia"), null);
        if (d == null) throw new Bad("data obrigatória");
        int n = svc.deleteDay(d);
        ctx.json(Map.of("ok", true, "deleted", n, "dia", Str.d(d)));
    }

    private void day(Context ctx) {
        Service svc = service(ctx);
        LocalDate d = Service.parseDate(ctx.queryParam("dia"), ponto.model.Time.today());
        List<Entry> rows = svc.on(d);
        String lastOut = "";
        LocalTime best = null;
        for (Entry e : rows) if (e.tout() != null && (best == null || e.tout().isAfter(best))) best = e.tout();
        if (best != null) lastOut = Str.t(best);
        List<Entry> open = rows.stream().filter(e -> e.tin() != null && e.tout() == null).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dia", Str.d(d));
        out.put("diaIso", Str.iso(d));
        out.put("d3", Str.d3(d));
        out.put("holiday", svc.holiday(d));
        out.put("falta", svc.falta(d));
        out.put("normal", svc.normal(d));
        out.put("lastOut", lastOut);
        out.put("open", open.stream().map(e -> entryJson(svc, e)).toList());
        out.put("entries", rows.stream().map(e -> entryJson(svc, e)).toList());
        ctx.json(out);
    }

    private void holiday(Context ctx) {
        Service svc = service(ctx);
        LocalDate d = Service.parseDate(ctx.queryParam("dia"), ponto.model.Time.today());
        ctx.json(Map.of("dia", Str.d(d), "off", svc.holiday(d)));
    }

    private void sum(Context ctx) {
        Service svc = service(ctx);
        LocalDate from = range(ctx, "from", true);
        LocalDate to = range(ctx, "to", false);
        if (to.isBefore(from)) { LocalDate x = from; from = to; to = x; }
        Report r = svc.report(from, to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", Str.d(from));
        out.put("to", Str.d(to));
        out.put("who", r.h().who());
        out.put("company", r.h().company());
        out.put("total", r.totalLabel());
        out.put("hours", r.totalDec());
        out.put("lines", r.lines().size());
        out.put("open", r.openLines());
        out.put("saldo", r.saldoLabel());
        out.put("saldoMins", r.saldoMins());
        out.put("faltas", r.faltas());
        out.put("plano", r.planoLabel());
        out.put("days", r.summary().stream().map(Json::jsonDay).toList());
        ctx.json(out);
    }

    /** Month bank: saldo + plan, the days of the month chart, the 12-month history. */
    private void bank(Context ctx) {
        Service svc = service(ctx);
        String raw = ctx.queryParam("ym");
        YearMonth ym;
        if (raw == null || raw.isBlank()) ym = ponto.model.Time.month();
        else {
            try { ym = YearMonth.parse(raw); }
            catch (RuntimeException e) { throw new Bad("mês: '" + raw + "'  (AAAA-MM)"); }
        }
        Map<String, Object> out = new LinkedHashMap<>(Json.jsonBank(svc.bank(ym)));
        out.put("days", svc.bankDays(ym).stream().map(Json::jsonBankDay).toList());
        out.put("months", svc.history(ym, 12).stream().map(Json::jsonMonthSaldo).toList());
        ctx.json(out);
    }

    /** Mark or clear the whole-day falta flag (`{ "on": true|false }`). */
    private void falta(Context ctx) {
        Service svc = service(ctx);
        LocalDate d = Service.parseDate(ctx.pathParam("dia"), null);
        if (d == null) throw new Bad("data obrigatória");
        FaltaReq r = ctx.bodyAsClass(FaltaReq.class);
        boolean on = Boolean.TRUE.equals(r.on());
        String warn = on ? svc.faltaWarn(d) : "";
        svc.falta(d, on);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("dia", Str.d(d));
        out.put("diaIso", Str.iso(d));
        out.put("falta", on);
        out.put("warn", warn);
        ctx.json(out);
    }

    /** Mark or clear the whole-day "dia normal" flag (`{ "on": true|false }`). */
    private void normal(Context ctx) {
        Service svc = service(ctx);
        LocalDate d = Service.parseDate(ctx.pathParam("dia"), null);
        if (d == null) throw new Bad("data obrigatória");
        FlagReq r = ctx.bodyAsClass(FlagReq.class);
        boolean on = Boolean.TRUE.equals(r.on());
        String warn = on ? svc.normalWarn(d) : "";
        svc.normal(d, on);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("dia", Str.d(d));
        out.put("diaIso", Str.iso(d));
        out.put("normal", on);
        out.put("warn", warn);
        ctx.json(out);
    }

    private void export(Context ctx) {
        Service svc = service(ctx);
        String kind = ctx.pathParam("kind").toLowerCase();
        if (!List.of("pdf", "xls", "csv").contains(kind)) throw new Bad("exportar: pdf, xls ou csv");
        LocalDate from = range(ctx, "from", true);
        LocalDate to = range(ctx, "to", false);
        if (to.isBefore(from)) { LocalDate x = from; from = to; to = x; }
        byte[] bytes = write(svc, kind, from, to);
        String name = Ver.NAME + "-" + Str.iso(from) + "_" + Str.iso(to) + "." + kind;
        String mime = switch (kind) {
            case "pdf" -> "application/pdf";
            case "xls" -> "application/vnd.ms-excel";
            default -> "text/csv; charset=utf-8";
        };
        ctx.contentType(mime);
        ctx.header("Content-Disposition", "attachment; filename=\"" + name + "\"");
        ctx.result(bytes);
    }

    private void config(Context ctx) {
        Service svc = service(ctx);
        ConfigReq r = ctx.bodyAsClass(ConfigReq.class);
        ctx.json(svc.setConfig(r.who(), r.off(), r.daily(), r.flag(r.vacuum()), s -> { }));
    }

    private void companies(Context ctx) {
        Service svc = service(ctx);
        Company act = svc.companies().active();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("activeId", act.id());
        out.put("companies", svc.companies().list().stream().map(Json::json).toList());
        ctx.json(out);
    }

    private void companyCreate(Context ctx) {
        Service svc = service(ctx);
        CompanyReq r = ctx.bodyAsClass(CompanyReq.class);
        Company c = svc.companies().create(nz(r.name()), r.off(), r.daily());
        ctx.status(HttpStatus.CREATED).json(Json.json(c));
    }

    private void companyUpdate(Context ctx) {
        Service svc = service(ctx);
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        CompanyReq r = ctx.bodyAsClass(CompanyReq.class);
        ctx.json(Json.json(svc.companies().update(id, r.name(), r.off(), r.daily())));
    }

    private void companyDelete(Context ctx) {
        Service svc = service(ctx);
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        svc.companies().delete(id);
        ctx.json(Map.of("ok", true, "id", id));
    }

    private void companyActive(Context ctx) {
        Service svc = service(ctx);
        CompanyReq r = ctx.bodyAsClass(CompanyReq.class);
        if (r.id() == null) throw new Bad("id obrigatório");
        Company c = svc.companies().byId(r.id());
        Map<String, Object> out = new LinkedHashMap<>(Json.json(c));
        out.put("activeId", c.id());
        ctx.json(out);
    }

    /**
     * Temp file, render, read, delete: the writers take a Path, the HTTP
     * response is built from bytes. (The terminal flow calls the writers
     * directly on its target file.)
     */
    private byte[] write(Service svc, String kind, LocalDate from, LocalDate to) {
        Report r = svc.report(from, to);
        if (r.lines().isEmpty()) throw new Problema("nada entre " + new Period(from, to).label());
        try {
            Path tmp = java.nio.file.Files.createTempFile("ponto-", "." + kind);
            try {
                return switch (kind) {
                    case "csv" -> Csv.write(tmp, r);
                    case "xls" -> Xls.write(tmp, r);
                    case "pdf" -> Pdf.write(tmp, r);
                    default -> throw new Bad("exportar: " + kind);
                };
            } finally {
                java.nio.file.Files.deleteIfExists(tmp);
            }
        } catch (java.io.IOException e) {
            throw new Problema("exportar: " + e.getMessage());
        }
    }

    private static int intParam(Context ctx, String name, int def) {
        String v = ctx.queryParam(name);
        if (v == null || v.isBlank()) return def;
        try { return Math.max(0, Integer.parseInt(v)); }
        catch (NumberFormatException e) { throw new Bad("valor inválido para " + name + ": '" + v + "'"); }
    }

    private LocalDate range(Context ctx, String name, boolean start) {
        Service svc = service(ctx);
        String q = ctx.queryParam(name);
        if (q != null && !q.isBlank()) return Service.parseDate(q, null);
        Map<String, Object> home = svc.home();
        String fallback = String.valueOf(home.get(start ? "min" : "max"));
        if (fallback == null || fallback.isBlank() || fallback.equals("null"))
            return start ? ponto.model.Time.today().withDayOfMonth(1) : ponto.model.Time.today();
        return Service.parseDate(fallback, start ? ponto.model.Time.today().withDayOfMonth(1) : ponto.model.Time.today());
    }

    private static String nz(String s) { return s == null ? "" : s; }

    record EntryReq(String dia, String tin, String tout, String just,
                    Boolean closeOpen, Boolean confirmMidnight, Boolean confirmOverlap) {
        boolean flag(Boolean value) { return Boolean.TRUE.equals(value); }
    }
    record ConfigReq(String who, String off, String daily, Boolean vacuum) {
        boolean flag(Boolean value) { return Boolean.TRUE.equals(value); }
    }

    record FaltaReq(Boolean on) { }

    record FlagReq(Boolean on) { }

    record CompanyReq(Integer id, String name, String off, String daily) { }
}
