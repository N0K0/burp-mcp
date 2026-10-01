package burp.mcp.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token-bucket rate limiter keyed by source IP.
 * Each IP gets rateLimit tokens per minute with 10% burst allowance.
 * Inactive entries auto-expire after 5 minutes.
 */
public class RateLimiter {

    private volatile int rateLimitPerMinute;
    private volatile int burstSize;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private volatile long lastCleanup = System.currentTimeMillis();
    private static final long CLEANUP_INTERVAL_MS = 60_000;
    private static final long IDLE_EXPIRE_MS = 5 * 60_000;
    private static final int MAX_BUCKETS = 10_000;

    public RateLimiter(int rateLimitPerMinute) {
        this.rateLimitPerMinute = rateLimitPerMinute;
        this.burstSize = Math.max(1, rateLimitPerMinute / 10);
    }

    /**
     * Try to consume a token. Returns seconds until next token available (0 = allowed).
     * A configured limit of 0 or less disables rate limiting.
     */
    public synchronized long tryConsume(String ip) {
        if (rateLimitPerMinute <= 0) {
            return 0;
        }
        cleanupIfNeeded();
        if (buckets.size() >= MAX_BUCKETS && !buckets.containsKey(ip)) {
            // Fail open when bucket table is full rather than growing without bound.
            return 0;
        }
        Bucket bucket = buckets.computeIfAbsent(ip, k -> new Bucket());
        return bucket.tryConsume();
    }

    public synchronized void updateRateLimit(int newRate) {
        if (newRate == this.rateLimitPerMinute) {
            return;
        }
        this.rateLimitPerMinute = newRate;
        this.burstSize = Math.max(1, newRate / 10);
        // Clear state on rate change
        buckets.clear();
    }

    private void cleanupIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastCleanup > CLEANUP_INTERVAL_MS) {
            lastCleanup = now;
            buckets.entrySet().removeIf(e -> (now - e.getValue().lastAccess) > IDLE_EXPIRE_MS);
        }
    }

    private class Bucket {
        double tokens = burstSize;
        long lastRefill = System.currentTimeMillis();
        long lastAccess = System.currentTimeMillis();

        synchronized long tryConsume() {
            lastAccess = System.currentTimeMillis();
            long now = lastAccess;
            // Refill: tokens_per_ms = rateLimitPerMinute / 60000.0
            double elapsed = (now - lastRefill) * rateLimitPerMinute / 60_000.0;
            tokens = Math.min(burstSize, tokens + elapsed);
            lastRefill = now;

            if (tokens >= 1.0) {
                tokens -= 1.0;
                return 0;
            }
            if (rateLimitPerMinute <= 0) {
                return 0;
            }
            // Return seconds until next token
            return (long) Math.ceil((1.0 - tokens) * 60_000.0 / rateLimitPerMinute);
        }
    }
}
