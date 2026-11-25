package ponto.app;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Lightweight bounded in-process read cache (LRU + short TTL + domain-version
 * invalidation). Replaces an external Redis daemon on single-instance Raspberry
 * Pi deployments: zero extra container RAM, sub-millisecond hits, atomic
 * write-through invalidation.
 */
public final class ReadCache {

    public enum Domain { COMPANY, TIMESHEET, NOTES, KANBAN, DIARIO }

    private static final int DEFAULT_MAX_ENTRIES = 64;
    private static final long DEFAULT_TTL_NANOS = TimeUnit.SECONDS.toNanos(60);

    private record DomainKey(int companyId, Domain domain) { }
    private record CacheKey(int companyId, Domain domain, long version, String key) { }
    private record Slot(Object value, long expiresAt) { }

    private final int maxEntries;
    private final long ttlNanos;
    private final Map<DomainKey, Long> versions = new HashMap<>();
    private final LinkedHashMap<CacheKey, Slot> entries;

    public ReadCache() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_TTL_NANOS);
    }

    ReadCache(int maxEntries, long ttlNanos) {
        this.maxEntries = Math.max(8, maxEntries);
        this.ttlNanos = Math.max(TimeUnit.MILLISECONDS.toNanos(10), ttlNanos);
        this.entries = new LinkedHashMap<>(32, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, Slot> eldest) {
                return size() > ReadCache.this.maxEntries;
            }
        };
    }

    @SuppressWarnings("unchecked")
    public <T> T get(int companyId, Domain domain, String key, Supplier<T> loader) {
        long now = System.nanoTime();
        long ver;
        synchronized (this) {
            ver = versionOf(companyId, domain);
            CacheKey ck = new CacheKey(companyId, domain, ver, key);
            Slot hit = entries.get(ck);
            if (hit != null) {
                if (hit.expiresAt() > now) return (T) hit.value();
                entries.remove(ck);
            }
        }
        T loaded = loader.get();
        if (loaded == null) return null;
        long storeAt = System.nanoTime();
        synchronized (this) {
            // Only populate if no write bumped the domain version while loading.
            if (versionOf(companyId, domain) == ver) {
                entries.put(new CacheKey(companyId, domain, ver, key), new Slot(loaded, storeAt + ttlNanos));
            }
        }
        return loaded;
    }

    public synchronized void invalidate(int companyId, Domain... domains) {
        for (Domain d : domains) {
            DomainKey dk = new DomainKey(companyId, d);
            versions.put(dk, versions.getOrDefault(dk, 0L) + 1L);
            for (Iterator<CacheKey> it = entries.keySet().iterator(); it.hasNext(); ) {
                CacheKey ck = it.next();
                if (ck.companyId() == companyId && ck.domain() == d) it.remove();
            }
        }
    }

    public synchronized void invalidateDomain(Domain... domains) {
        for (Domain d : domains) {
            for (DomainKey dk : java.util.List.copyOf(versions.keySet())) {
                if (dk.domain() == d) versions.put(dk, versions.getOrDefault(dk, 0L) + 1L);
            }
            // Also bump global companyId=0 sentinel used by catalog entries.
            DomainKey global = new DomainKey(0, d);
            versions.put(global, versions.getOrDefault(global, 0L) + 1L);
            for (Iterator<CacheKey> it = entries.keySet().iterator(); it.hasNext(); ) {
                if (it.next().domain() == d) it.remove();
            }
        }
    }

    public synchronized void invalidateAll() {
        for (DomainKey dk : java.util.List.copyOf(versions.keySet())) {
            versions.put(dk, versions.getOrDefault(dk, 0L) + 1L);
        }
        entries.clear();
    }

    synchronized int size() { return entries.size(); }

    private long versionOf(int companyId, Domain domain) {
        return versions.getOrDefault(new DomainKey(companyId, domain), 0L);
    }
}
