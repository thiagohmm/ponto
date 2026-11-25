package ponto.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WSL2 helpers: detect environment, resolve the Windows host IP, open the
 * Windows browser. In WSL, {@code 127.0.0.1} is the Linux VM — APIs published
 * on Windows need the host address from {@code /etc/resolv.conf}.
 */
public final class Wsl {

    private static final Pattern NAMESERVER = Pattern.compile("^\\s*nameserver\\s+(\\S+)", Pattern.MULTILINE);
    private static final Boolean DETECTED = detect();

    public static boolean isWsl() {
        return DETECTED;
    }

    /** First nameserver from resolv.conf (Windows host in default WSL2 NAT). */
    public static String windowsHostIp() {
        if (!isWsl()) return null;
        try {
            String text = Files.readString(Path.of("/etc/resolv.conf"));
            Matcher m = NAMESERVER.matcher(text);
            if (m.find()) {
                String ip = m.group(1).trim();
                if (!ip.isEmpty() && !ip.equalsIgnoreCase("localhost")) return ip;
            }
        } catch (IOException ignored) {
            // fall through
        }
        return null;
    }

    /**
     * Rewrites {@code localhost} / {@code 127.0.0.1} / {@code [::1]} in the URL
     * host to {@code windowsHost}. Leaves other hosts untouched.
     */
    public static String mapLocalhostToWindows(String url, String windowsHost) {
        if (url == null || url.isBlank() || windowsHost == null || windowsHost.isBlank()) return url;
        try {
            java.net.URI u = java.net.URI.create(url.trim());
            String host = u.getHost();
            if (host == null) return url;
            String h = host.toLowerCase(Locale.ROOT);
            if (!h.equals("localhost") && !h.equals("127.0.0.1") && !h.equals("[::1]") && !h.equals("::1")) {
                return url;
            }
            StringBuilder out = new StringBuilder();
            if (u.getScheme() != null) out.append(u.getScheme()).append("://");
            if (u.getUserInfo() != null) out.append(u.getUserInfo()).append('@');
            out.append(windowsHost);
            if (u.getPort() >= 0) out.append(':').append(u.getPort());
            if (u.getRawPath() != null) out.append(u.getRawPath());
            if (u.getRawQuery() != null) out.append('?').append(u.getRawQuery());
            if (u.getRawFragment() != null) out.append('#').append(u.getRawFragment());
            return out.toString();
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    /** Best-effort: open URL in the Windows default browser. */
    public static boolean openWindowsBrowser(String url) {
        if (url == null || url.isBlank()) return false;
        Path cmd = Path.of("/mnt/c/Windows/System32/cmd.exe");
        if (!Files.isRegularFile(cmd)) cmd = Path.of("/mnt/c/windows/system32/cmd.exe");
        if (!Files.isRegularFile(cmd)) return false;
        try {
            Process p = new ProcessBuilder(cmd.toString(), "/c", "start", "", url)
                    .redirectErrorStream(true)
                    .start();
            // drain so the process can exit
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                while (r.readLine() != null) { /* discard */ }
            }
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) return false;
        try {
            String v = Files.readString(Path.of("/proc/version")).toLowerCase(Locale.ROOT);
            if (v.contains("microsoft") || v.contains("wsl")) return true;
        } catch (IOException ignored) {
            // fall through
        }
        return System.getenv("WSL_DISTRO_NAME") != null || System.getenv("WSL_INTEROP") != null;
    }

    private Wsl() { }
}
