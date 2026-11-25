package ponto.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Sends HTTP requests from this machine via {@link HttpClient}. */
public final class HttpExecutor {

    public record Header(String name, String value) { }

    public record Request(
            String method,
            String url,
            List<Header> headers,
            String body,
            boolean followRedirects,
            int timeoutSeconds
    ) { }

    public record Response(
            int status,
            String statusText,
            List<Header> headers,
            String body,
            boolean bodyBase64,
            String contentType,
            long elapsedMs,
            String error
    ) { }

    private final HttpClient.Builder clientBuilder;

    public HttpExecutor() {
        this(HttpClient.newBuilder());
    }

    /** Test hook. */
    public HttpExecutor(HttpClient.Builder clientBuilder) {
        this.clientBuilder = clientBuilder;
    }

    public Response send(Request req) {
        long t0 = System.nanoTime();
        try {
            if (req == null || req.url() == null || req.url().isBlank()) {
                return fail(t0, "URL obrigatória.");
            }
            String method = req.method() == null || req.method().isBlank()
                    ? "GET" : req.method().trim().toUpperCase(Locale.ROOT);
            URI uri = URI.create(req.url().trim());
            int timeout = req.timeoutSeconds() <= 0 ? 30 : Math.min(req.timeoutSeconds(), 300);

            HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(timeout));

            byte[] bodyBytes = req.body() == null || req.body().isEmpty()
                    ? new byte[0]
                    : req.body().getBytes(StandardCharsets.UTF_8);

            boolean hasBody = bodyBytes.length > 0
                    || method.equals("POST") || method.equals("PUT") || method.equals("PATCH");

            if (hasBody) {
                b.method(method, HttpRequest.BodyPublishers.ofByteArray(bodyBytes));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }

            if (req.headers() != null) {
                for (Header h : req.headers()) {
                    if (h == null || h.name() == null || h.name().isBlank()) continue;
                    String name = h.name().trim();
                    if (isRestricted(name)) continue;
                    b.header(name, h.value() == null ? "" : h.value());
                }
            }

            HttpClient.Redirect redirect = req.followRedirects()
                    ? HttpClient.Redirect.NORMAL
                    : HttpClient.Redirect.NEVER;
            HttpClient client = clientBuilder
                    .connectTimeout(Duration.ofSeconds(Math.min(timeout, 30)))
                    .followRedirects(redirect)
                    .build();

            HttpResponse<byte[]> res = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            long ms = (System.nanoTime() - t0) / 1_000_000L;

            List<Header> outHeaders = new ArrayList<>();
            res.headers().map().forEach((name, values) -> {
                for (String v : values) outHeaders.add(new Header(name, v));
            });

            String contentType = res.headers().firstValue("content-type").orElse("");
            byte[] bytes = res.body() == null ? new byte[0] : res.body();
            boolean binary = looksBinary(contentType, bytes);
            String body;
            if (binary) {
                body = Base64.getEncoder().encodeToString(bytes);
            } else {
                Charset cs = charsetFrom(contentType);
                body = new String(bytes, cs);
            }

            return new Response(
                    res.statusCode(),
                    statusText(res.statusCode()),
                    outHeaders,
                    body,
                    binary,
                    contentType,
                    ms,
                    null
            );
        } catch (IllegalArgumentException e) {
            return fail(t0, e.getMessage() == null ? "URL inválida." : e.getMessage());
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) msg = e.getClass().getSimpleName();
            return fail(t0, msg);
        }
    }

    private static Response fail(long t0, String error) {
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        return new Response(0, "", List.of(), "", false, "", ms, error);
    }

    private static boolean isRestricted(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("host") || n.equals("content-length") || n.equals("connection")
                || n.equals("expect") || n.equals("upgrade") || n.equals("http2-settings");
    }

    private static boolean looksBinary(String contentType, byte[] bytes) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("json") || ct.contains("text/") || ct.contains("xml")
                || ct.contains("javascript") || ct.contains("svg") || ct.contains("csv")) {
            return false;
        }
        if (ct.contains("octet-stream") || ct.contains("image/") || ct.contains("audio/")
                || ct.contains("video/") || ct.contains("zip") || ct.contains("pdf")) {
            return true;
        }
        int lim = Math.min(bytes.length, 512);
        for (int i = 0; i < lim; i++) {
            int b = bytes[i] & 0xff;
            if (b == 0) return true;
        }
        return false;
    }

    private static Charset charsetFrom(String contentType) {
        if (contentType == null) return StandardCharsets.UTF_8;
        String lower = contentType.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("charset=");
        if (idx < 0) return StandardCharsets.UTF_8;
        String cs = contentType.substring(idx + 8).trim();
        int semi = cs.indexOf(';');
        if (semi >= 0) cs = cs.substring(0, semi).trim();
        cs = cs.replace("\"", "");
        try {
            return Charset.forName(cs);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static String statusText(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 304 -> "Not Modified";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            default -> "";
        };
    }

    /** Convenience: map headers from JSON-friendly structure. */
    public static List<Header> headersFromMap(Map<String, String> map) {
        if (map == null || map.isEmpty()) return List.of();
        List<Header> list = new ArrayList<>();
        map.forEach((k, v) -> list.add(new Header(k, v)));
        return list;
    }

    public static Map<String, String> headersToMap(List<Header> headers) {
        Map<String, String> map = new LinkedHashMap<>();
        if (headers == null) return map;
        for (Header h : headers) {
            if (h == null || h.name() == null) continue;
            map.put(h.name(), h.value());
        }
        return map;
    }
}
