package ponto.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Keeps the Windows display and system awake while {@code ponto-http} is running,
 * using Win32 {@code SetThreadExecutionState(ES_CONTINUOUS | ES_DISPLAY_REQUIRED | ES_SYSTEM_REQUIRED)}
 * and restoring {@code SetThreadExecutionState(ES_CONTINUOUS)} on stop/exit.
 *
 * <p>Works on both native Windows and WSL2 by spawning a hidden {@code powershell.exe}
 * helper process via {@code -EncodedCommand} (avoiding CLI quote stripping on Windows).
 * A single native thread asserts and periodically refreshes the execution state while
 * waiting on stdin EOF (0% CPU). Closing stdin (normal stop, Ctrl+C shutdown hook, or
 * abrupt JVM exit) unblocks the helper immediately so it restores normal sleep behavior
 * and exits cleanly.
 */
public final class KeepScreenOn {

    /**
     * C# 5-compatible snippet (works on Windows PowerShell 5.1 / .NET Framework 4.x
     * as well as PowerShell 7+). Passed via {@code -EncodedCommand} (UTF-16LE Base64)
     * so quotes inside {@code [DllImport("kernel32.dll")]} are never stripped.
     */
    static final String POWERSHELL_SCRIPT =
            "Add-Type -TypeDefinition '\n"
            + "using System;\n"
            + "using System.Runtime.InteropServices;\n"
            + "using System.Threading;\n"
            + "public static class KeepScreenOnNative {\n"
            + "    private const uint ES_CONTINUOUS = 0x80000000u;\n"
            + "    private const uint ES_SYSTEM_REQUIRED = 0x00000001u;\n"
            + "    private const uint ES_DISPLAY_REQUIRED = 0x00000002u;\n"
            + "    [DllImport(\"kernel32.dll\")]\n"
            + "    private static extern uint SetThreadExecutionState(uint esFlags);\n"
            + "    public static int Run() {\n"
            + "        uint flags = ES_CONTINUOUS | ES_DISPLAY_REQUIRED | ES_SYSTEM_REQUIRED;\n"
            + "        if (SetThreadExecutionState(flags) == 0) {\n"
            + "            Console.WriteLine(\"ERR\");\n"
            + "            Console.Out.Flush();\n"
            + "            return 1;\n"
            + "        }\n"
            + "        Console.WriteLine(\"OK\");\n"
            + "        Console.Out.Flush();\n"
            + "        using (var stop = new ManualResetEventSlim(false)) {\n"
            + "            var reader = new Thread(() => {\n"
            + "                try { Console.In.ReadLine(); } catch { }\n"
            + "                stop.Set();\n"
            + "            });\n"
            + "            reader.IsBackground = true;\n"
            + "            reader.Start();\n"
            + "            while (!stop.Wait(30000)) {\n"
            + "                SetThreadExecutionState(flags);\n"
            + "            }\n"
            + "        }\n"
            + "        SetThreadExecutionState(ES_CONTINUOUS);\n"
            + "        return 0;\n"
            + "    }\n"
            + "}\n"
            + "'\n"
            + "exit [KeepScreenOnNative]::Run()\n";

    private static final long START_TIMEOUT_SECONDS = 20;
    private static final long STOP_TIMEOUT_SECONDS = 3;
    private static final int MAX_ERROR_LENGTH = 400;

    @FunctionalInterface
    interface ProcessLauncher {
        Process start(List<String> command) throws IOException;
    }

    private record ReadyResult(boolean ok, String error) { }

    private final Object lock = new Object();
    private final String powershellPath;
    private final ProcessLauncher launcher;
    private final long startTimeoutSeconds;

    private Process process;
    private boolean active;
    private boolean starting;
    private String lastError;

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

    static String encodePowerShell(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
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

    public String lastError() {
        synchronized (lock) {
            return lastError;
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
            this.lastError = null;
            try {
                p = launcher.start(buildCommand(powershellPath));
            } catch (IOException e) {
                this.lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                return false;
            }
            this.process = p;
            this.starting = true;
        }

        ReadyResult res = waitForReady(p, startTimeoutSeconds);

        synchronized (lock) {
            this.starting = false;
            if (this.process != p) {
                // stop() was called concurrently while starting
                terminate(p);
                return false;
            }
            if (res.ok && p.isAlive()) {
                this.active = true;
                this.lastError = null;
                return true;
            }
            this.process = null;
            this.active = false;
            this.lastError = res.error != null ? res.error : "processo auxiliar encerrou inesperadamente";
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
            this.lastError = null;
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
                "-EncodedCommand", encodePowerShell(POWERSHELL_SCRIPT)
        );
    }

    private static ReadyResult waitForReady(Process p, long timeoutSeconds) {
        CompletableFuture<ReadyResult> ready = CompletableFuture.supplyAsync(() -> {
            StringBuilder diag = new StringBuilder();
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if ("OK".equals(trimmed)) return new ReadyResult(true, null);
                    if ("ERR".equals(trimmed)) {
                        return new ReadyResult(false, "SetThreadExecutionState retornou 0");
                    }
                    if (!trimmed.isEmpty() && diag.length() < MAX_ERROR_LENGTH) {
                        if (diag.length() > 0) diag.append(" | ");
                        diag.append(trimmed);
                    }
                }
                String msg = diag.isEmpty()
                        ? "helper PowerShell encerrou sem confirmar ativação"
                        : truncate(diag.toString());
                return new ReadyResult(false, msg);
            } catch (IOException e) {
                return new ReadyResult(false, e.getMessage() == null ? "erro de leitura" : e.getMessage());
            }
        });
        try {
            return ready.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            ready.cancel(true);
            return new ReadyResult(false, "timeout após " + timeoutSeconds + "s aguardando PowerShell");
        } catch (Exception e) {
            ready.cancel(true);
            return new ReadyResult(false, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static String truncate(String s) {
        return s.length() <= MAX_ERROR_LENGTH ? s : s.substring(0, MAX_ERROR_LENGTH) + "...";
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
