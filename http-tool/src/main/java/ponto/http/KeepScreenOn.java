package ponto.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the Windows display and system awake while {@code ponto-http} is running,
 * using Win32 {@code SetThreadExecutionState(ES_CONTINUOUS | ES_DISPLAY_REQUIRED | ES_SYSTEM_REQUIRED)}
 * and restoring {@code SetThreadExecutionState(ES_CONTINUOUS)} on stop/exit.
 *
 * <p>Works on both native Windows and WSL2 by spawning a hidden {@code powershell.exe}
 * helper process that blocks on stdin (0% CPU). Closing stdin (normal stop, Ctrl+C
 * shutdown hook, or abrupt JVM exit) unblocks the helper so it restores normal sleep
 * behavior and exits cleanly.
 */
public final class KeepScreenOn {

    /**
     * C# 5-compatible snippet (works on Windows PowerShell 5.1 / .NET Framework 4.x
     * as well as PowerShell 7+).
     */
    static final String POWERSHELL_SCRIPT =
            "Add-Type -TypeDefinition '"
            + "using System.Runtime.InteropServices; "
            + "public static class KeepScreenOnNative { "
            + "private const uint ES_CONTINUOUS = 0x80000000u; "
            + "private const uint ES_SYSTEM_REQUIRED = 0x00000001u; "
            + "private const uint ES_DISPLAY_REQUIRED = 0x00000002u; "
            + "[DllImport(\"kernel32.dll\")] "
            + "private static extern uint SetThreadExecutionState(uint esFlags); "
            + "public static uint Enable() { return SetThreadExecutionState(ES_CONTINUOUS | ES_DISPLAY_REQUIRED | ES_SYSTEM_REQUIRED); } "
            + "public static uint Restore() { return SetThreadExecutionState(ES_CONTINUOUS); } "
            + "}'; "
            + "if ([KeepScreenOnNative]::Enable() -eq 0) { Write-Output 'ERR'; exit 1 }; "
            + "Write-Output 'OK'; [Console]::Out.Flush(); "
            + "$null = [Console]::In.ReadLine(); "
            + "[void][KeepScreenOnNative]::Restore()";

    private static final long START_TIMEOUT_SECONDS = 15;
    private static final long STOP_TIMEOUT_SECONDS = 3;

    @FunctionalInterface
    interface ProcessLauncher {
        Process start(List<String> command) throws IOException;
    }

    private final Object lock = new Object();
    private final String powershellPath;
    private final ProcessLauncher launcher;
    private final long startTimeoutSeconds;

    private Process process;
    private boolean active;
    private boolean starting;

    public KeepScreenOn() {
        this(resolvePowerShell(System.getProperty("os.name", ""), Wsl.isWsl()),
                cmd -> new ProcessBuilder(cmd).redirectErrorStream(true).start(),
                START_TIMEOUT_SECONDS);
    }

    KeepScreenOn(String powershellPath, ProcessLauncher launcher, long startTimeoutSeconds) {
        this.powershellPath = powershellPath;
        this.launcher = launcher;
        this.startTimeoutSeconds = startTimeoutSeconds;
    }

    static String resolvePowerShell(String osName, boolean isWsl) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "powershell.exe";
        }
        if (isWsl) {
            Path ps = Path.of("/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe");
            if (Files.isRegularFile(ps)) return ps.toString();
            Path psLower = Path.of("/mnt/c/windows/system32/windowspowershell/v1.0/powershell.exe");
            if (Files.isRegularFile(psLower)) return psLower.toString();
        }
        return null;
    }

    public boolean isSupported() {
        return powershellPath != null && !powershellPath.isBlank();
    }

    public boolean isActive() {
        synchronized (lock) {
            return active && process != null && process.isAlive();
        }
    }

    public boolean isStarting() {
        synchronized (lock) {
            return starting;
        }
    }

    /**
     * Activates keep-screen-on (idempotent). Returns {@code true} if active.
     */
    public boolean start() {
        Process p;
        synchronized (lock) {
            if (!isSupported()) return false;
            if (active && process != null && process.isAlive()) return true;
            if (process != null) {
                terminate(process);
                process = null;
                active = false;
            }
            try {
                p = launcher.start(buildCommand(powershellPath));
            } catch (IOException e) {
                return false;
            }
            this.process = p;
            this.starting = true;
        }

        boolean ok = waitForReady(p, startTimeoutSeconds);

        synchronized (lock) {
            this.starting = false;
            if (this.process != p) {
                // stop() was called concurrently while starting
                terminate(p);
                return false;
            }
            if (ok && p.isAlive()) {
                this.active = true;
                return true;
            }
            this.process = null;
            this.active = false;
        }
        terminate(p);
        return false;
    }

    /**
     * Restores normal sleep behavior and terminates the helper process.
     */
    public void stop() {
        Process p;
        synchronized (lock) {
            p = this.process;
            this.process = null;
            this.active = false;
            this.starting = false;
        }
        if (p != null) {
            terminate(p);
        }
    }

    static List<String> buildCommand(String powershellPath) {
        return List.of(
                powershellPath,
                "-NoProfile",
                "-NonInteractive",
                "-WindowStyle", "Hidden",
                "-Command", POWERSHELL_SCRIPT
        );
    }

    private static boolean waitForReady(Process p, long timeoutSeconds) {
        CompletableFuture<Boolean> ready = CompletableFuture.supplyAsync(() -> {
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if ("OK".equals(trimmed)) return true;
                    if ("ERR".equals(trimmed)) return false;
                }
                return false;
            } catch (IOException e) {
                return false;
            }
        });
        try {
            return Boolean.TRUE.equals(ready.get(timeoutSeconds, TimeUnit.SECONDS));
        } catch (Exception e) {
            ready.cancel(true);
            return false;
        }
    }

    private static void terminate(Process p) {
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
            // best-effort signal via EOF
        }
        try {
            if (!p.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }
}
