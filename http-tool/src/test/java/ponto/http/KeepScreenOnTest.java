package ponto.http;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class KeepScreenOnTest {

    @Test
    void unsupportedEnvironmentDoesNothing() {
        KeepScreenOn k = new KeepScreenOn(null, cmd -> {
            fail("launcher should not be called when unsupported");
            return null;
        }, 2);
        assertFalse(k.isSupported());
        assertFalse(k.isActive());
        assertFalse(k.start());
        assertFalse(k.isActive());
        assertNull(k.lastError());
        k.stop();
    }

    @Test
    void resolvesPowerShellOnWindowsAndLinux() {
        assertEquals("powershell.exe", KeepScreenOn.resolvePowerShell("Windows 11", false));
        assertNull(KeepScreenOn.resolvePowerShell("Linux", false));
        assertNull(KeepScreenOn.resolvePowerShell("Mac OS X", false));
    }

    @Test
    void startIsIdempotentAndStopRestoresCleanly() {
        AtomicInteger starts = new AtomicInteger();
        FakeProcess proc = new FakeProcess("OK\n", true);
        KeepScreenOn k = new KeepScreenOn("powershell.exe", cmd -> {
            starts.incrementAndGet();
            assertEquals("powershell.exe", cmd.get(0));
            assertTrue(cmd.contains("-NoProfile"));
            assertTrue(cmd.contains("-WindowStyle"));
            assertTrue(cmd.contains("-EncodedCommand"));
            assertFalse(cmd.contains("-Command"));
            return proc;
        }, 2);

        assertTrue(k.isSupported());
        assertTrue(k.start());
        assertTrue(k.isActive());
        assertNull(k.lastError());
        assertEquals(1, starts.get());

        // Second start is a no-op while active
        assertTrue(k.start());
        assertEquals(1, starts.get());

        k.stop();
        assertFalse(k.isActive());
        assertTrue(proc.stdinClosed);
        assertFalse(proc.isAlive());
    }

    @Test
    void returnsFalseAndRecordsErrorWhenHelperReportsErr() {
        FakeProcess proc = new FakeProcess("ERR\n", false);
        KeepScreenOn k = new KeepScreenOn("powershell.exe", cmd -> proc, 2);

        assertFalse(k.start());
        assertFalse(k.isActive());
        assertFalse(proc.isAlive());
        assertNotNull(k.lastError());
        assertTrue(k.lastError().contains("SetThreadExecutionState"));
    }

    @Test
    void capturesDiagnosticOutputWhenHelperFailsUnexpectedly() {
        FakeProcess proc = new FakeProcess("Add-Type : compilation failed\n", false);
        KeepScreenOn k = new KeepScreenOn("powershell.exe", cmd -> proc, 2);

        assertFalse(k.start());
        assertFalse(k.isActive());
        assertNotNull(k.lastError());
        assertTrue(k.lastError().contains("compilation failed"));
    }

    @Test
    void returnsFalseAndRecordsErrorWhenLauncherThrows() {
        KeepScreenOn k = new KeepScreenOn("powershell.exe", cmd -> {
            throw new IOException("not found");
        }, 2);

        assertFalse(k.start());
        assertFalse(k.isActive());
        assertEquals("not found", k.lastError());
    }

    @Test
    void buildCommandUsesEncodedUtf16LeWithWin32ConstantsAndRefreshLoop() {
        List<String> cmd = KeepScreenOn.buildCommand("powershell.exe");
        assertTrue(cmd.contains("-EncodedCommand"));
        assertFalse(cmd.contains("-Command"));
        String encoded = cmd.get(cmd.size() - 1);
        String script = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_16LE);

        assertTrue(script.contains("0x80000000u"));
        assertTrue(script.contains("0x00000001u"));
        assertTrue(script.contains("0x00000002u"));
        assertTrue(script.contains("[DllImport(\"kernel32.dll\")]"));
        assertTrue(script.contains("SetThreadExecutionState"));
        assertTrue(script.contains("ManualResetEventSlim"));
    }

    private static final class FakeProcess extends Process {
        private final InputStream stdout;
        private final OutputStream stdin;
        private final CountDownLatch exited = new CountDownLatch(1);
        private volatile boolean alive;
        volatile boolean stdinClosed;

        FakeProcess(String initialOutput, boolean stayAliveUntilStdinClose) {
            this.alive = stayAliveUntilStdinClose;
            if (!stayAliveUntilStdinClose) {
                this.exited.countDown();
            }
            this.stdout = new ByteArrayInputStream(initialOutput.getBytes(StandardCharsets.UTF_8));
            this.stdin = new OutputStream() {
                @Override
                public void write(int b) { }

                @Override
                public void close() {
                    stdinClosed = true;
                    alive = false;
                    exited.countDown();
                }
            };
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() throws InterruptedException {
            exited.await();
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exited.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            if (alive) throw new IllegalThreadStateException();
            return 0;
        }

        @Override
        public void destroy() {
            alive = false;
            exited.countDown();
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }
}
