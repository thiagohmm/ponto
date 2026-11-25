package ponto.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses common curl command lines into an HTTP request draft.
 * Supports: URL, -X/--request, -H/--header, -d/--data and --data-raw/--data-binary,
 * -u/--user, --url, quotes and backslash line continuations.
 */
public final class CurlParser {

    public record Draft(
            String method,
            String url,
            Map<String, String> headers,
            String body
    ) { }

    public static Draft parse(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Cole um comando curl.");
        String normalized = raw.trim()
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\\\n", " ")
                .replace("\\\r\n", " ");
        List<String> tokens = tokenize(normalized);
        if (tokens.isEmpty()) throw new IllegalArgumentException("Cole um comando curl.");

        int start = 0;
        if (tokens.get(0).equalsIgnoreCase("curl")) start = 1;

        String method = null;
        String url = null;
        Map<String, String> headers = new LinkedHashMap<>();
        StringBuilder body = new StringBuilder();
        boolean bodySet = false;
        String user = null;

        for (int i = start; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equals("-X") || t.equals("--request")) {
                method = requireArg(tokens, ++i, t).toUpperCase(Locale.ROOT);
            } else if (t.startsWith("-X") && t.length() > 2) {
                method = t.substring(2).toUpperCase(Locale.ROOT);
            } else if (t.equals("-H") || t.equals("--header")) {
                putHeader(headers, requireArg(tokens, ++i, t));
            } else if (t.startsWith("-H") && t.length() > 2 && !t.equals("-H")) {
                putHeader(headers, t.substring(2));
            } else if (t.equals("-d") || t.equals("--data") || t.equals("--data-raw")
                    || t.equals("--data-binary") || t.equals("--data-ascii")) {
                if (bodySet) body.append('&');
                body.append(requireArg(tokens, ++i, t));
                bodySet = true;
                if (method == null) method = "POST";
            } else if (t.equals("-u") || t.equals("--user")) {
                user = requireArg(tokens, ++i, t);
            } else if (t.equals("--url")) {
                url = requireArg(tokens, ++i, t);
            } else if (t.equals("-G") || t.equals("--get")) {
                method = "GET";
            } else if (t.equals("-I") || t.equals("--head")) {
                method = "HEAD";
            } else if (t.equals("-A") || t.equals("--user-agent")) {
                headers.putIfAbsent("User-Agent", requireArg(tokens, ++i, t));
            } else if (t.equals("-e") || t.equals("--referer")) {
                headers.putIfAbsent("Referer", requireArg(tokens, ++i, t));
            } else if (t.startsWith("-") && !t.equals("-")) {
                // skip unknown flags; consume value if next token is not a flag/url-ish
                if (i + 1 < tokens.size() && !tokens.get(i + 1).startsWith("-")
                        && !looksLikeUrl(tokens.get(i + 1))) {
                    i++;
                }
            } else if (looksLikeUrl(t) || url == null) {
                url = stripQuotes(t);
            }
        }

        if (url == null || url.isBlank()) throw new IllegalArgumentException("URL não encontrada no curl.");
        if (user != null && !user.isBlank()) {
            String token = Base64.getEncoder().encodeToString(user.getBytes(StandardCharsets.UTF_8));
            headers.putIfAbsent("Authorization", "Basic " + token);
        }
        if (method == null) method = bodySet ? "POST" : "GET";
        if (bodySet && !headers.containsKey("Content-Type") && !headers.containsKey("content-type")) {
            headers.put("Content-Type", "application/x-www-form-urlencoded");
        }
        return new Draft(method, url, headers, bodySet ? body.toString() : "");
    }

    private static void putHeader(Map<String, String> headers, String line) {
        String h = stripQuotes(line);
        int colon = h.indexOf(':');
        if (colon <= 0) throw new IllegalArgumentException("Header inválido: " + h);
        String name = h.substring(0, colon).trim();
        String value = h.substring(colon + 1).trim();
        headers.put(name, value);
    }

    private static String requireArg(List<String> tokens, int i, String flag) {
        if (i >= tokens.size()) throw new IllegalArgumentException("Falta valor para " + flag);
        return stripQuotes(tokens.get(i));
    }

    private static boolean looksLikeUrl(String t) {
        String s = stripQuotes(t).toLowerCase(Locale.ROOT);
        return s.startsWith("http://") || s.startsWith("https://") || s.startsWith("http:") || s.startsWith("https:");
    }

    static String stripQuotes(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.length() >= 2) {
            char a = s.charAt(0);
            char b = s.charAt(s.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    /** Shell-ish tokenizer: whitespace split, respecting single/double quotes. */
    static List<String> tokenize(String input) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (inSingle) {
                if (c == '\'') inSingle = false;
                else cur.append(c);
                continue;
            }
            if (inDouble) {
                if (c == '\\' && i + 1 < input.length()) {
                    char n = input.charAt(i + 1);
                    if (n == '"' || n == '\\' || n == '$' || n == '`') {
                        cur.append(n);
                        i++;
                        continue;
                    }
                }
                if (c == '"') inDouble = false;
                else cur.append(c);
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                continue;
            }
            if (c == '"') {
                inDouble = true;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            cur.append(c);
        }
        if (inSingle || inDouble) throw new IllegalArgumentException("Aspas não fechadas no curl.");
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private CurlParser() { }
}
