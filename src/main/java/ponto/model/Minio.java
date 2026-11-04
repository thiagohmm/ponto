package ponto.model;

import ponto.Problema;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;

/**
 * The smallest S3 client that does the job: bucket check/create, put, get,
 * delete. AWS Signature V4, path-style access, no SDK — MinIO or S3.
 *
 * Config, all from the environment:
 *   PONTO_S3_ENDPOINT   e.g. http://minio:9000 (empty = storage disabled)
 *   PONTO_S3_ACCESS     access key     (default "minioadmin")
 *   PONTO_S3_SECRET     secret key     (default "minioadmin")
 *   PONTO_S3_BUCKET     (default "ponto")
 *   PONTO_S3_REGION     (default "us-east-1")
 */
public final class Minio {

    public static final String ENDPOINT = "PONTO_S3_ENDPOINT";
    public static final String ACCESS = "PONTO_S3_ACCESS";
    public static final String SECRET = "PONTO_S3_SECRET";
    public static final String BUCKET = "PONTO_S3_BUCKET";
    public static final String REGION = "PONTO_S3_REGION";

    private static final Duration CONNECT = Duration.ofSeconds(5);
    private static final Duration REQUEST = Duration.ofMinutes(10);

    private final URI endpoint;
    private final String access;
    private final String secret;
    private final String bucket;
    private final String region;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT).build();

    private Minio(URI endpoint, String access, String secret, String bucket, String region) {
        this.endpoint = endpoint;
        this.access = access;
        this.secret = secret;
        this.bucket = bucket;
        this.region = region;
    }

    /** Read the environment; null when no endpoint is configured. */
    public static Minio env() {
        String ep = nz(System.getenv(ENDPOINT));
        if (ep.isEmpty()) return null;
        if (!ep.startsWith("http://") && !ep.startsWith("https://"))
            throw new Problema("s3: " + ENDPOINT + " precisa de http(s)");
        String url = ep;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        try {
            return new Minio(URI.create(url),
                    nz(System.getenv(ACCESS), "minioadmin"),
                    nz(System.getenv(SECRET), "minioadmin"),
                    nz(System.getenv(BUCKET), "ponto"),
                    nz(System.getenv(REGION), "us-east-1"));
        } catch (IllegalArgumentException e) {
            throw new Problema("s3: " + ENDPOINT + " não é uma URL");
        }
    }

    /** true when the bucket answers (creating it if it is missing). */
    public boolean up() {
        try { ensureBucket(); return true; }
        catch (Problema e) { return false; }
    }

    /** Head the bucket; create it when it is missing. */
    public void ensureBucket() {
        Response head = req("HEAD", null, null, null);
        if (head.status() == 200 || head.status() == 403) return;
        if (head.status() != 404) throw fail(head, "bucket");
        Response put = req("PUT", null, null, null);
        if (put.status() >= 300) throw fail(put, "bucket");
    }

    /** Upload an object; the content type travels as the object metadata. */
    public void put(String key, byte[] body, String contentType) {
        Response r = req("PUT", key, body, nz(contentType, "application/octet-stream"));
        if (r.status() >= 300) throw fail(r, "put " + key);
    }

    /** Download an object. */
    public byte[] get(String key) {
        Response r = req("GET", key, null, null);
        if (r.status() >= 300) throw fail(r, "get " + key);
        return r.body() == null ? new byte[0] : r.body();
    }

    /** Delete an object; a missing object is not an error here. */
    public void delete(String key) {
        Response r = req("DELETE", key, null, null);
        if (r.status() >= 300 && r.status() != 404) throw fail(r, "delete " + key);
    }

    // ---------------------------------------------------------------- HTTP

    private record Response(int status, byte[] body, String text) { }

    /**
     * key is null for bucket operations. The client sends the Host header
     * itself (it is a restricted header): we sign exactly that value.
     */
    private Response req(String method, String key, byte[] body, String contentType) {
        try {
            String path = "/" + bucket + (key == null ? "" : "/" + encPath(key));
            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            String amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
                    .withZone(ZoneOffset.UTC).format(now);
            String dateStamp = amzDate.substring(0, 8);
            String scope = dateStamp + "/" + region + "/s3/aws4_request";
            String payloadHash = hex(sha256(body == null ? new byte[0] : body));

            String signed = "host;x-amz-content-sha256;x-amz-date";
            String headersBlock = "host:" + hostHeader() + "\n"
                    + "x-amz-content-sha256:" + payloadHash + "\n"
                    + "x-amz-date:" + amzDate + "\n";
            String canonical = method + "\n" + path + "\n" + "\n"
                    + headersBlock + "\n" + signed + "\n" + payloadHash;
            String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                    + hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
            byte[] k = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), dateStamp);
            k = hmac(k, region);
            k = hmac(k, "s3");
            k = hmac(k, "aws4_request");
            String auth = "AWS4-HMAC-SHA256 Credential=" + access + "/" + scope
                    + ", SignedHeaders=" + signed + ", Signature=" + hex(hmac(k, stringToSign));

            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + path))
                    .method(method, body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(body))
                    .header("x-amz-content-sha256", payloadHash)
                    .header("x-amz-date", amzDate)
                    .header("Authorization", auth)
                    .timeout(REQUEST);
            if (contentType != null) b.header("Content-Type", contentType);
            HttpResponse<byte[]> res = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] raw = res.body() == null ? new byte[0] : res.body();
            return new Response(res.statusCode(), raw, new String(raw, StandardCharsets.UTF_8));
        } catch (Problema e) {
            throw e;
        } catch (Exception e) {
            throw new Problema("s3: " + nz(e.getMessage()));
        }
    }

    private String hostHeader() {
        int port = endpoint.getPort();
        return port == -1 ? endpoint.getHost() : endpoint.getHost() + ":" + port;
    }

    /** S3 percent-encoding, per path segment (RFC 3986 unreserved stay). */
    private static String encPath(String key) {
        StringBuilder sb = new StringBuilder();
        for (String seg : key.split("/", -1)) {
            if (!sb.isEmpty()) sb.append('/');
            for (byte b : seg.getBytes(StandardCharsets.UTF_8)) {
                int c = b & 0xFF;
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                        || c == '-' || c == '.' || c == '_' || c == '~') sb.append((char) c);
                else sb.append('%').append("0123456789ABCDEF".charAt(c >> 4)).append("0123456789ABCDEF".charAt(c & 15));
            }
        }
        return sb.toString();
    }

    /** Surface the message the S3 service printed in its XML error body. */
    private static Problema fail(Response r, String what) {
        String msg = r.text();
        int i = msg.indexOf("<Message>");
        int j = i < 0 ? -1 : msg.indexOf("</Message>", i);
        if (i >= 0 && j > i) msg = msg.substring(i + 9, j);
        if (msg.isBlank()) msg = r.status() + " " + what;
        return new Problema("s3 " + what + ": " + msg);
    }

    // ---------------------------------------------------------------- sig

    private static byte[] sha256(byte[] in) {
        try { return MessageDigest.getInstance("SHA-256").digest(in); }
        catch (Exception e) { throw new Problema("sha256"); }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new Problema("hmac"); }
    }

    private static String hex(byte[] in) {
        StringBuilder sb = new StringBuilder(in.length * 2);
        for (byte b : in) sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return sb.toString();
    }

    private static String nz(String v) { return v == null || v.isBlank() ? "" : v.trim(); }

    private static String nz(String v, String def) { return v == null || v.isBlank() ? def : v.trim(); }
}
