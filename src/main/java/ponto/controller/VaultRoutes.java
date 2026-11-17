package ponto.controller;

import ponto.Bad;
import ponto.VaultSealed;
import ponto.app.Service;
import ponto.model.VaultCrypto;
import ponto.model.VaultDraft;
import ponto.model.VaultItem;
import ponto.model.VaultMeta;
import ponto.model.VaultRewrap;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * HTTP adapter for the vault — and the reason the feature is honest.
 *
 * Look at what crosses this boundary: base64 text, a company tag, a revision number.
 * No route here parses a blob, no log line could contain a password, and the master
 * password is never even a parameter. {@link #meta} is the handshake: it hands the
 * browser the salt and iteration count so it can derive the key <em>locally</em>,
 * then the client proves it holds that key by opening the verifier blob — a check
 * the server cannot perform and cannot fake, because it does not have the key.
 *
 * Unsealed writes answer 423 Locked with {@code need: "create"}, which is the SPA's
 * existing shape for "do this first" (see midnight and overlap).
 */
final class VaultRoutes {

    private final Function<Context, Service> service;
    VaultRoutes(Function<Context, Service> service) { this.service = service; }

    void register(Javalin app) {
        app.get("/api/vault", this::meta);
        app.post("/api/vault", this::create);
        app.get("/api/vault/items", this::items);
        app.post("/api/vault/items", this::itemCreate);
        app.post("/api/vault/items/bulk", this::itemsCreate);
        app.put("/api/vault/items/{id}", this::itemUpdate);
        app.delete("/api/vault/items/{id}", this::itemDelete);
        app.delete("/api/vault/company", this::companyWipe);
        app.post("/api/vault/rekey", this::rekey);
    }

    // ---- handshake -----------------------------------------------------------

    /** Public parameters plus the verifier: everything needed to unlock, nothing to read. */
    private void meta(Context ctx) {
        ctx.json(json(service.apply(ctx).vaultMeta()));
    }

    /** Seal a new vault. The salt and the verifier arrive from the client; 409 if it exists. */
    private void create(Context ctx) {
        Service svc = service.apply(ctx);
        MetaReq r = ctx.bodyAsClass(MetaReq.class);
        VaultMeta m = svc.vaultCreate(nz(r.kdf()), nz(r.salt()), iters(r.iterations()), nz(r.check()));
        ctx.status(HttpStatus.CREATED).json(json(m));
    }

    // ---- records -------------------------------------------------------------

    /** Every credential, or one company's with {@code ?company=7}. Blobs stay sealed. */
    private void items(Context ctx) {
        Service svc = service.apply(ctx);
        ctx.json(Map.of("items", svc.vaultItems(company(ctx)).stream().map(item -> itemJson(item)).toList()));
    }

    private void itemCreate(Context ctx) {
        Service svc = service.apply(ctx);
        ItemReq r = ctx.bodyAsClass(ItemReq.class);
        if (r.companyId() == null || r.companyId() < 1) throw new Bad("empresa obligatory");
        VaultItem saved = svc.createVaultItem(r.companyId(), nz(r.blob()));
        ctx.status(HttpStatus.CREATED).json(itemJson(saved));
    }

    /** Import: insert many sealed records, or none of them. */
    private void itemsCreate(Context ctx) {
        Service svc = service.apply(ctx);
        ItemsReq r = ctx.bodyAsClass(ItemsReq.class);
        if (r.items() == null || r.items().isEmpty()) throw new Bad("nothing to import");
        List<VaultDraft> drafts = r.items().stream().map(d -> {
            if (d.companyId() == null || d.companyId() < 1) throw new Bad("empresa obligatory");
            return new VaultDraft(d.companyId(), nz(d.blob()));
        }).toList();
        List<VaultItem> saved = svc.createVaultItems(drafts);
        ctx.status(HttpStatus.CREATED).json(Map.of("imported", saved.size(),
                "items", saved.stream().map(item -> itemJson(item)).toList()));
    }

    private void itemUpdate(Context ctx) {
        Service svc = service.apply(ctx);
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        ItemReq r = ctx.bodyAsClass(ItemReq.class);
        if (r.revision() == null || r.revision() < 1) throw new Bad("revision obligatory");
        ctx.json(itemJson(svc.updateVaultItem(id, nz(r.blob()), r.revision())));
    }

    private void itemDelete(Context ctx) {
        Service svc = service.apply(ctx);
        int id = ctx.pathParamAsClass("id", Integer.class).get();
        svc.deleteVaultItem(id);
        ctx.json(Map.of("ok", true, "id", id));
    }

    /** Forget the active company's credentials. The other companies keep theirs. */
    private void companyWipe(Context ctx) {
        Service svc = service.apply(ctx);
        ctx.json(Map.of("ok", true, "deleted", svc.deleteVaultCompany(svc.companyId()),
                "company", svc.companyId()));
    }

    // ---- master password change -----------------------------------------------

    /**
     * Re-key: the client decrypts everything with the old key, re-seals with the new
     * one and sends the whole set back. One request, one transaction — the server
     * cannot do it in pieces even if it wanted to, because it cannot open a blob.
     */
    private void rekey(Context ctx) {
        Service svc = service.apply(ctx);
        RekeyReq r = ctx.bodyAsClass(RekeyReq.class);
        if (r.items() == null) throw new Bad("items obligatory");
        List<VaultRewrap> items = r.items().stream().map(e -> {
            if (e.id() == null || e.id() < 1) throw new Bad("id obligatory");
            if (e.revision() == null || e.revision() < 1) throw new Bad("revision obligatory");
            return new VaultRewrap(e.id(), nz(e.blob()), e.revision());
        }).toList();
        ctx.json(json(svc.vaultRekey(nz(r.kdf()), nz(r.salt()), iters(r.iterations()), nz(r.check()), items)));
    }

    // ---- shape ---------------------------------------------------------------

    /** The public parameters. Never the plaintext, never the key — there is neither. */
    private static Map<String, Object> json(VaultMeta m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sealed", m.sealed());
        out.put("kdf", m.kdf());
        out.put("salt", m.salt());
        out.put("iterations", m.iterations());
        out.put("check", m.check());
        out.put("items", m.items());
        out.put("updatedAt", m.upd());
        return out;
    }

    private static Map<String, Object> itemJson(VaultItem i) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", i.id());
        out.put("companyId", i.companyId());
        out.put("blob", i.blob());
        out.put("revision", i.revision());
        out.put("createdAt", i.crea());
        out.put("updatedAt", i.upd());
        return out;
    }

    /** {@code ?company=7}, or null for the whole vault. Blank is "all", not a silent 0. */
    private static Integer company(Context ctx) {
        String raw = ctx.queryParam("company");
        if (raw == null || raw.isBlank()) return null;
        try { return Integer.valueOf(raw.trim()); }
        catch (NumberFormatException e) { throw new Bad("company: '" + raw + "' non integer"); }
    }

    private static int iters(Integer raw) {
        int v = raw == null ? VaultCrypto.ITERATIONS : raw;
        if (v < VaultCrypto.MIN_ITERATIONS || v > VaultCrypto.MAX_ITERATIONS)
            throw new Bad("interações PBKDF2 fora de range");
        return v;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    public record MetaReq(String kdf, String salt, Integer iterations, String check) { }
    public record ItemReq(Integer companyId, String blob, Integer revision) { }
    public record ItemsReq(List<ItemReq> items) { }
    public record RewrapReq(Integer id, String blob, Integer revision) { }
    public record RekeyReq(String kdf, String salt, Integer iterations, String check, List<RewrapReq> items) { }
}
