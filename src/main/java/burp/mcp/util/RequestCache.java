package burp.mcp.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe request cache with TTL-based expiry, LRU eviction, and stats.
 * LRU eviction triggers when size exceeds maxEntries (default 1000).
 */
public class RequestCache {

    private static final int DEFAULT_MAX_ENTRIES = 1000;

    private final ConcurrentHashMap<String, CacheEntry> store;
    private final int ttlSeconds;
    private final int maxEntries;
    private java.util.concurrent.ScheduledExecutorService cleanupExecutor;
    private ScheduledFuture<?> cleanupTask;
    private volatile boolean running = true;

    // Master switch for the cache_enabled preference
    private volatile boolean enabled = true;

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    // Stats
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder evictions = new LongAdder();

    private static class CacheEntry {
        final String value;
        final long createdAt;
        volatile long lastAccess;

        CacheEntry(String value) {
            this.value = value;
            this.createdAt = this.lastAccess = System.currentTimeMillis();
        }

        boolean isExpired(int ttlSeconds) {
            return System.currentTimeMillis() - createdAt > ttlSeconds * 1000L;
        }
    }

    public RequestCache(int ttlSeconds) {
        this(ttlSeconds, DEFAULT_MAX_ENTRIES);
    }

    public RequestCache(int ttlSeconds, int maxEntries) {
        this.ttlSeconds = ttlSeconds;
        this.maxEntries = maxEntries;
        this.store = new ConcurrentHashMap<>(256);
        startCleanup();
    }

    public void put(String key, String value) {
        if (!enabled) return;
        if (store.size() >= maxEntries) {
            evictLru();
        }
        store.put(key, new CacheEntry(value));
    }

    public String get(String key) {
        if (!enabled) {
            misses.increment();
            return null;
        }
        CacheEntry entry = store.get(key);
        if (entry == null || entry.isExpired(ttlSeconds)) {
            // Only remove if the mapping still points at the expired entry,
            // so a concurrent put of a fresh value under the same key is kept.
            if (entry != null) {
                store.remove(key, entry);
            }
            misses.increment();
            return null;
        }
        entry.lastAccess = System.currentTimeMillis();
        hits.increment();
        return entry.value;
    }

    public void remove(String key) {
        store.remove(key);
    }

    public boolean contains(String key) {
        return get(key) != null;
    }

    public int size() {
        cleanup();
        return store.size();
    }

    public void clear() {
        store.clear();
    }

    public long getHits() { return hits.sum(); }
    public long getMisses() { return misses.sum(); }
    public long getEvictions() { return evictions.sum(); }

    private void evictLru() {
        long oldest = Long.MAX_VALUE;
        String oldestKey = null;
        for (Map.Entry<String, CacheEntry> e : store.entrySet()) {
            if (e.getValue().lastAccess < oldest) {
                oldest = e.getValue().lastAccess;
                oldestKey = e.getKey();
            }
        }
        if (oldestKey != null) {
            store.remove(oldestKey);
            evictions.increment();
        }
    }

    private void startCleanup() {
        if (ttlSeconds <= 0) {
            // TTL 0 means entries never expire on a timer; skip the scheduler
            // to avoid IllegalArgumentException from scheduleAtFixedRate.
            return;
        }
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "request-cache-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupTask = cleanupExecutor.scheduleAtFixedRate(this::cleanup, ttlSeconds, ttlSeconds, TimeUnit.SECONDS);
    }

    private void cleanup() {
        store.entrySet().removeIf(entry -> entry.getValue().isExpired(ttlSeconds));
    }

    public void shutdown() {
        running = false;
        if (cleanupTask != null) {
            cleanupTask.cancel(false);
            cleanupTask = null;
        }
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdownNow();
            cleanupExecutor = null;
        }
        store.clear();
    }
}
