package ponto.model;

import ponto.Bad;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The plaintext *inside* one sealed blob — the four fields the user typed:
 * application, user, password and description, plus the company tag repeated
 * inside the ciphertext so a row cannot be relabelled without re-sealing.
 *
 * Only the client ({@code web/src/crypto.ts}) and the terminal encode and parse
 * this JSON. It never crosses HTTP, sealed or unsealed: the API moves the blob
 * as an opaque string and has no idea what is written on it.
 */
public final class VaultPayload {

    public static final int APP_MAX = 200;
    public static final int USER_MAX = 200;
    public static final int PASS_MAX = 512;
    public static final int DESC_MAX = 4_000;

    /** The only keys a payload may carry — anything else is a leaked field. */
    public static final String[] KEYS = { "app", "user", "pass", "desc", "company" };

    public record Plain(String app, String user, String pass, String desc, int companyId) {
        public Plain {
            app = app == null ? "" : app;
            user = user == null ? "" : user;
            pass = pass == null ? "" : pass;
            desc = desc == null ? "" : desc;
        }

        /** "Gmail · ana" — the line the terminal and the list show. */
        public String label() {
            String head = app.isEmpty() ? "(sin nome)" : app;
            return user.isEmpty() ? head : head + " · " + user;
        }

        public boolean blank() { return app.isEmpty() && user.isEmpty() && pass.isEmpty() && desc.isEmpty(); }
    }

    private static final tools.jackson.databind.json.JsonMapper MAPPER =
            new tools.jackson.databind.json.JsonMapper();

    /** The JSON the browser encrypts. Compact, stable key order. */
    public static String json(Plain p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("app", p.app());
        m.put("user", p.user());
        m.put("pass", p.pass());
        m.put("desc", p.desc());
        m.put("company", p.companyId());
        return MAPPER.writeValueAsString(m);
    }

    /**
     * Parse a decrypted payload, refusing unknown keys and oversized fields.
     * Untrusted input (an imported file, a paste) lands here, so the rules are
     * strict on purpose: a key we did not design is not silently accepted.
     */
    public static Plain parse(String raw) {
        if (raw == null || raw.isBlank()) throw new Bad("cofre: record empty");
        var tree = read(raw);
        for (var it : tree.propertyNames()) {
            boolean known = false;
            for (String k : KEYS) if (k.equals(it)) known = true;
            if (!known) throw new Bad("cofre: campo unknown '" + it + "'");
        }
        String app = text(tree, "app"), user = text(tree, "user");
        String pass = text(tree, "pass"), desc = text(tree, "desc");
        int company = integer(tree, "company");
        tooLong(app, APP_MAX, "aplicativo"); tooLong(user, USER_MAX, "usuario");
        tooLong(pass, PASS_MAX, "senha"); tooLong(desc, DESC_MAX, "descrição");
        if (company < 1) throw new Bad("cofre: empresa invalida");
        return new Plain(app.trim(), user.trim(), pass, desc.trim(), company);
    }

    private static tools.jackson.databind.JsonNode read(String raw) {
        try { return new tools.jackson.databind.json.JsonMapper().readTree(raw); }
        catch (RuntimeException e) { throw new Bad("cofre: JSON corrupto"); }
    }

    private static String text(tools.jackson.databind.JsonNode tree, String key) {
        if (!tree.has(key)) return "";
        var n = tree.get(key);
        if (n == null || n.isNull()) return "";
        if (!n.isString()) throw new Bad("cofre: '" + key + "' não texto");
        return n.asString("");
    }

    private static int integer(tools.jackson.databind.JsonNode tree, String key) {
        var n = tree.get(key);
        if (n == null || n.isNull()) throw new Bad("cofre: falta '" + key + "'");
        if (!n.isNumber()) throw new Bad("cofre: '" + key + "' não é number");
        return n.asInt(0);
    }

    private static void tooLong(String v, int max, String what) {
        if (v.length() > max) throw new Bad("cofre: " + what + " mais longa que " + max + " chars");
    }
}
