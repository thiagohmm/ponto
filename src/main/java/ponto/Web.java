package ponto;

import ponto.model.Db;
import ponto.model.Entry;
import ponto.model.Holidays;
import ponto.model.Report;
import ponto.model.Service;
import ponto.model.Str;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.staticfiles.Location;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HTTP API + static React build. Same Service as the terminal menu. */
final class Web {

    private final Service svc;
    private final int port;

    Web(Db db, int port) {
        this.svc = new Service(db);
        this.port = port;
        Holidays.extra(Service.offDates(db.cfg("off")));
    }

    void start() {
        Javalin app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.jsonMapper(new Json3());
            if (Web.class.getResource("/public/index.html") != null) {
                cfg.staticFiles.add(s -> {
                    s.hostedPath = "/";
                    s.directory = "/public";
                    s.location = Location.CLASSPATH;
                });
                cfg.spaRoot.addFile("/", "/public/index.html", Location.CLASSPATH);
            }
        });

        app.exception(NeedConfirm.class, (e, ctx) -> {
            ctx.status(409).json(Map.of("need", e.code, "message", e.getMessage()));
        });
        app.exception(Bad.class, (e, ctx) -> ctx.status(400).json(Map.of("error", e.getMessage())));
        app.exception(Problema.class, (e, ctx) -> ctx.status(400).json(Map.of("error", e.getMessage())));
        app.exception(Exception.class, (e, ctx) -> {
            ctx.status(500).json(Map.of("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        });

        app.get("/api/home", ctx -> ctx.json(svc.home()));
        app.get("/api/entries", this::list);
        app.get("/api/entries/{id}", this::one);
        app.post("/api/entries", this::create);
        app.put("/api/entries/{id}", this::update);
        app.delete("/api/entries/{id}", this::delete);
        app.delete("/api/days/{dia}", this::deleteDay);
        app.get("/api/day", this::day);
        app.get("/api/holiday", this::holiday);
        app.get("/api/sum", this::sum);
        app.get("/api/export/{kind}", this::export);
        app.get("/api/config", ctx -> ctx.json(svc.config()));
        app.put("/api/config", this::config);

        app.start("0.0.0.0", port);
        System.out.println("ponto web  http://0.0.0.0:" + port + "   db " + svc.db().file);
        try { Thread.currentThread().join(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        app.stop();
    }

    private void list(Context ctx) {
        String q = ctx.queryParam("q");
        String dia = ctx.queryParam("dia");
        int n = intParam(ctx, "n", 40);
        int skip = intParam(ctx, "skip", 0);
        List<Entry> rows = svc.list(q, dia, n, skip);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", svc.count(q, dia));
        out.put("shown", rows.size());
        out.put("skip", skip);
        out.put("entries", rows.stream().map(Service::json).toList());
        ctx.json(out);
    }

    private void one(Context ctx) {
        ctx.json(Service.json(svc.byId(ctx.pathParamAsClass("id", Integer.class).get())));
    }

    private void create(Context ctx) {
        SaveReq r = ctx.bodyAsClass(SaveReq.class);
        LocalDate dia = Service.parseDate(r.dia(), LocalDate.now());
        LocalTime tin = Service.parseTime(r.tin());
        LocalTime tout = Service.parseTime(r.tout());
        Entry e = svc.create(dia, tin, tout, nz(r.just()), r.flag(r.closeOpen()),
                r.flag(r.confirmMidnight()), r.flag(r.confirmOverlap()));
        ctx.status(HttpStatus.CREATED).json(Service.json(e));
    }

    private void update(Context ctx) {
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        SaveReq r = ctx.bodyAsClass(SaveReq.class);
        Entry cur = svc.byId(id);
        LocalDate dia = Service.parseDate(r.dia(), cur.dia());
        LocalTime tin = r.tin() == null ? cur.tin() : Service.parseTime(r.tin());
        LocalTime tout = r.tout() == null ? cur.tout() : Service.parseTime(r.tout());
        String just = r.just() == null ? cur.just() : r.just();
        Entry e = svc.update(id, dia, tin, tout, just, r.flag(r.confirmMidnight()), r.flag(r.confirmOverlap()));
        ctx.json(Service.json(e));
    }

    private void delete(Context ctx) {
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        svc.delete(id);
        ctx.json(Map.of("ok", true, "id", id));
    }

    private void deleteDay(Context ctx) {
        LocalDate d = Service.parseDate(ctx.pathParam("dia"), null);
        if (d == null) throw new Bad("date required");
        int n = svc.deleteDay(d);
        ctx.json(Map.of("ok", true, "deleted", n, "dia", Str.d(d)));
    }

    private void day(Context ctx) {
        LocalDate d = Service.parseDate(ctx.queryParam("dia"), LocalDate.now());
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
        out.put("lastOut", lastOut);
        out.put("open", open.stream().map(Service::json).toList());
        out.put("entries", rows.stream().map(Service::json).toList());
        ctx.json(out);
    }

    private void holiday(Context ctx) {
        LocalDate d = Service.parseDate(ctx.queryParam("dia"), LocalDate.now());
        ctx.json(Map.of("dia", Str.d(d), "off", svc.holiday(d)));
    }

    private void sum(Context ctx) {
        LocalDate from = range(ctx, "from", true);
        LocalDate to = range(ctx, "to", false);
        if (to.isBefore(from)) { LocalDate x = from; from = to; to = x; }
        Report r = svc.report(from, to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", Str.d(from));
        out.put("to", Str.d(to));
        out.put("who", r.h().who());
        out.put("total", r.totalLabel());
        out.put("hours", r.totalDec());
        out.put("lines", r.lines().size());
        out.put("open", r.openLines());
        out.put("days", r.summary().stream().map(Service::jsonDay).toList());
        ctx.json(out);
    }

    private void export(Context ctx) {
        String kind = ctx.pathParam("kind").toLowerCase();
        if (!List.of("pdf", "xls", "csv").contains(kind)) throw new Bad("export: pdf, xls or csv");
        LocalDate from = range(ctx, "from", true);
        LocalDate to = range(ctx, "to", false);
        if (to.isBefore(from)) { LocalDate x = from; from = to; to = x; }
        byte[] bytes = svc.export(kind, from, to);
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
        SaveReq r = ctx.bodyAsClass(SaveReq.class);
        ctx.json(svc.setConfig(r.who(), r.off(), r.flag(r.vacuum())));
    }

    private static int intParam(Context ctx, String name, int def) {
        String v = ctx.queryParam(name);
        if (v == null || v.isBlank()) return def;
        try { return Math.max(0, Integer.parseInt(v)); }
        catch (NumberFormatException e) { throw new Bad(name + ": '" + v + "'"); }
    }

    private LocalDate range(Context ctx, String name, boolean start) {
        String q = ctx.queryParam(name);
        if (q != null && !q.isBlank()) return Service.parseDate(q, null);
        Map<String, Object> home = svc.home();
        String fallback = String.valueOf(home.get(start ? "min" : "max"));
        if (fallback == null || fallback.isBlank() || fallback.equals("null"))
            return start ? LocalDate.now().withDayOfMonth(1) : LocalDate.now();
        return Service.parseDate(fallback, start ? LocalDate.now().withDayOfMonth(1) : LocalDate.now());
    }

    private static String nz(String s) { return s == null ? "" : s; }

    record SaveReq(String dia, String tin, String tout, String just,
                   Boolean closeOpen, Boolean confirmMidnight, Boolean confirmOverlap,
                   String who, String off, Boolean vacuum) {
        boolean flag(Boolean v) { return Boolean.TRUE.equals(v); }
    }
}
