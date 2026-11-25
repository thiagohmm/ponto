package ponto.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Persists the collection JSON on disk so every browser hitting this local
 * server sees the same requests (not browser-local localStorage).
 *
 * Default path: {@code ~/.local/share/ponto-http/collection.json}
 * (or {@code %USERPROFILE%\.ponto-http\collection.json} on Windows).
 */
public final class CollectionStore {

    private final Path file;
    private final tools.jackson.databind.json.JsonMapper json = new tools.jackson.databind.json.JsonMapper();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile long revision;

    public CollectionStore(Path file) {
        this.file = file;
        this.revision = Files.exists(file) ? file.toFile().lastModified() : 0L;
    }

    public static CollectionStore defaultStore() {
        return new CollectionStore(defaultPath());
    }

    public static Path defaultPath() {
        String override = System.getenv("PONTO_HTTP_DATA");
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim()).resolve("collection.json");
        }
        String home = System.getProperty("user.home", ".");
        String os = System.getProperty("os.name", "").toLowerCase();
        Path dir;
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            dir = (local != null && !local.isBlank())
                    ? Path.of(local, "ponto-http")
                    : Path.of(home, ".ponto-http");
        } else {
            Path xdg = Path.of(home, ".local", "share", "ponto-http");
            dir = xdg;
        }
        return dir.resolve("collection.json");
    }

    public Path path() {
        return file;
    }

    public long revision() {
        return revision;
    }

    /** Empty collection skeleton. */
    public Map<String, Object> empty() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", "Minha collection");
        out.put("activeId", null);
        out.put("items", java.util.List.of());
        out.put("revision", revision);
        return out;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> load() throws IOException {
        lock.readLock().lock();
        try {
            if (!Files.isRegularFile(file)) {
                return empty();
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.isBlank()) return empty();
            Map<String, Object> data = json.readValue(text, Map.class);
            if (data == null) data = empty();
            data.put("revision", revision);
            return data;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Map<String, Object> save(Map<String, Object> data) throws IOException {
        if (data == null) throw new IllegalArgumentException("Collection vazia.");
        lock.writeLock().lock();
        try {
            Files.createDirectories(file.getParent());
            Map<String, Object> copy = new LinkedHashMap<>(data);
            copy.remove("revision");
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            byte[] bytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(copy);
            Files.write(tmp, bytes);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            revision = System.currentTimeMillis();
            copy.put("revision", revision);
            return copy;
        } catch (IOException e) {
            // atomic move can fail across filesystems; retry plain replace
            try {
                Map<String, Object> copy = new LinkedHashMap<>(data);
                copy.remove("revision");
                byte[] bytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(copy);
                Files.write(file, bytes);
                revision = System.currentTimeMillis();
                copy.put("revision", revision);
                return copy;
            } catch (IOException e2) {
                e2.addSuppressed(e);
                throw e2;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
}
