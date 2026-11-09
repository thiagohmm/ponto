package ponto.controller;

import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Single-user web gate: env credentials, in-memory sessions, login rate limit. */
public final class Auth {

    public static final String COOKIE = "ponto_session";
    private static final long SESSION_TTL_SEC = 7L * 24 * 60 * 60;
    private static final int LOGIN_MAX = 5;
    private static final long LOGIN_WINDOW_MS = 60_000;

    private final String user;
    private final byte[] password;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Instant> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

    public Auth(String user, String password) {
        this.user = user;
        this.password = password.getBytes(StandardCharsets.UTF_8);
    }

    /** Null when both env vars are absent (local tests). Both must be set together. */
    public static Auth fromEnv() {
        String user = System.getenv("PONTO_WEB_USER");
        String pass = System.getenv("PONTO_WEB_PASSWORD");
        boolean missingUser = user == null || user.isBlank();
        boolean missingPass = pass == null || pass.isBlank();
        if (missingUser && missingPass) return null;
        if (missingUser || missingPass)
            throw new ponto.Problema("Defina PONTO_WEB_USER e PONTO_WEB_PASSWORD juntos.");
        return new Auth(user.trim(), pass);
    }

    public boolean open(Context ctx) {
        String token = ctx.cookie(COOKIE);
        if (token == null || token.isBlank()) return false;
        Instant exp = sessions.get(token);
        if (exp == null) return false;
        if (exp.isBefore(Instant.now())) {
            sessions.remove(token);
            return false;
        }
        sessions.put(token, Instant.now().plusSeconds(SESSION_TTL_SEC));
        return true;
    }

    public void require(Context ctx) {
        if (!open(ctx)) throw new UnauthorizedResponse("Faça login para continuar.");
    }

    public Map<String, Object> login(Context ctx, String name, String pass) {
        String ip = client(ctx);
        if (!allowLogin(ip))
            throw new UnauthorizedResponse("Muitas tentativas. Aguarde um minuto.");
        byte[] gotUser = name == null ? new byte[0] : name.trim().getBytes(StandardCharsets.UTF_8);
        byte[] gotPass = pass == null ? new byte[0] : pass.getBytes(StandardCharsets.UTF_8);
        boolean ok = slowEquals(gotUser, user.getBytes(StandardCharsets.UTF_8))
                && slowEquals(gotPass, password);
        if (!ok) {
            recordAttempt(ip);
            throw new UnauthorizedResponse("Usuário ou senha inválidos.");
        }
        attempts.remove(ip);
        String token = newToken();
        sessions.put(token, Instant.now().plusSeconds(SESSION_TTL_SEC));
        setCookie(ctx, token, (int) SESSION_TTL_SEC);
        return Map.of("ok", true, "user", user);
    }

    public Map<String, Object> me() {
        return Map.of("user", user);
    }

    public void logout(Context ctx) {
        String token = ctx.cookie(COOKIE);
        if (token != null) sessions.remove(token);
        setCookie(ctx, "", 0);
    }

    private void setCookie(Context ctx, String value, int maxAge) {
        StringBuilder cookie = new StringBuilder(COOKIE).append('=').append(value)
                .append("; Path=/; HttpOnly; SameSite=Lax; Max-Age=").append(maxAge);
        if (secure(ctx)) cookie.append("; Secure");
        ctx.header("Set-Cookie", cookie.toString());
    }

    static boolean secure(Context ctx) {
        String proto = ctx.header("X-Forwarded-Proto");
        if (proto != null && proto.toLowerCase().startsWith("https")) return true;
        String forwarded = ctx.header("Forwarded");
        return forwarded != null && forwarded.toLowerCase().contains("proto=https");
    }

    private boolean allowLogin(String ip) {
        Deque<Long> q = attempts.get(ip);
        if (q == null) return true;
        long cut = System.currentTimeMillis() - LOGIN_WINDOW_MS;
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() < cut) q.removeFirst();
            return q.size() < LOGIN_MAX;
        }
    }

    private void recordAttempt(String ip) {
        Deque<Long> q = attempts.computeIfAbsent(ip, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        long cut = now - LOGIN_WINDOW_MS;
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() < cut) q.removeFirst();
            q.addLast(now);
        }
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String client(Context ctx) {
        String forwarded = ctx.header("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
        }
        return ctx.ip() == null ? "unknown" : ctx.ip();
    }

    /** Length-independent compare so password length is not leaked by early return. */
    static boolean slowEquals(byte[] a, byte[] b) {
        int diff = a.length ^ b.length;
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            byte x = i < a.length ? a[i] : 0;
            byte y = i < b.length ? b[i] : 0;
            diff |= x ^ y;
        }
        return diff == 0;
    }

    /** Drop expired sessions occasionally (idle cleanup). */
    void sweep() {
        Instant now = Instant.now();
        for (Iterator<Map.Entry<String, Instant>> it = sessions.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().isBefore(now)) it.remove();
        }
    }
}
