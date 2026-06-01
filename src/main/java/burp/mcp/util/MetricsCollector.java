package burp.mcp.util;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe metrics collector for the MCP server.
 * Tracks request counts, latencies (ring buffer), tool call counts,
 * error counts, and uptime. Supports a Prometheus-style export.
 */
public class MetricsCollector {

    private static final int LATENCY_RING_SIZE = 1000;
    private static final int RATE_WINDOW_SECONDS = 60;
    private static final int RATE_SLOTS = 60; // 1-second granularity

    private final long startTimeMs = System.currentTimeMillis();

    // Request counts
    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder errorCount = new LongAdder();
    private final LongAdder rateLimitedCount = new LongAdder();

    // Rolling rate: array of counts per second, index = current second % 60
    private final long[] rateSlots = new long[RATE_SLOTS];
    private volatile int currentRateSlot = 0;
    private volatile long lastRateSlotRoll = System.currentTimeMillis();

    // Latency ring buffer
    private final long[] latencyRing = new long[LATENCY_RING_SIZE];
    private volatile int latencyWritePos = 0;
    private volatile int latencyCount = 0;
    private final Object latencyLock = new Object();

    // Per-tool call counts
    private final ConcurrentHashMap<String, LongAdder> toolCounts = new ConcurrentHashMap<>();

    // Cache stats (updated externally by RequestCache)
    private volatile long cacheHits = 0;
    private volatile long cacheMisses = 0;

    // Active connections
    private volatile int activeConnections = 0;

    public void recordRequest() {
        totalRequests.increment();
        rollRateSlot();
        rateSlots[currentRateSlot]++;
    }

    public void recordError() {
        errorCount.increment();
    }

    public void recordRateLimited() {
        rateLimitedCount.increment();
    }

    public void recordLatencyMs(long ms) {
        synchronized (latencyLock) {
            latencyRing[latencyWritePos % LATENCY_RING_SIZE] = ms;
            latencyWritePos++;
            if (latencyCount < LATENCY_RING_SIZE) latencyCount = Math.min(latencyWritePos, LATENCY_RING_SIZE);
        }
    }

    public void recordToolCall(String toolName) {
        toolCounts.computeIfAbsent(toolName, k -> new LongAdder()).increment();
    }

    public void setCacheStats(long hits, long misses) {
        this.cacheHits = hits;
        this.cacheMisses = misses;
    }

    public void setActiveConnections(int n) {
        this.activeConnections = n;
    }

    public long getUptimeSeconds() {
        return (System.currentTimeMillis() - startTimeMs) / 1000;
    }

    public long getTotalRequests() {
        return totalRequests.sum();
    }

    public long getErrorCount() {
        return errorCount.sum();
    }

    public long getRateLimitedCount() {
        return rateLimitedCount.sum();
    }

    public long getRequestsPerMinute() {
        long now = System.currentTimeMillis();
        long windowEnd = currentRateSlot;
        long count = 0;
        // Sum last 60 seconds worth of slots, walking backward
        for (int i = 0; i < RATE_SLOTS; i++) {
            int idx = (int) ((windowEnd - i + RATE_SLOTS) % RATE_SLOTS);
            count += rateSlots[idx];
        }
        return count;
    }

    public double getAvgLatencyMs() {
        synchronized (latencyLock) {
            int n = latencyCount;
            if (n == 0) return 0;
            long sum = 0;
            for (int i = 0; i < n; i++) sum += latencyRing[i % LATENCY_RING_SIZE];
            return (double) sum / n;
        }
    }

    public long getPercentileMs(double pct) {
        synchronized (latencyLock) {
            int n = latencyCount;
            if (n == 0) return 0;
            long[] sorted = new long[n];
            int start = Math.max(0, latencyWritePos - n);
            for (int i = 0; i < n; i++) sorted[i] = latencyRing[(start + i) % LATENCY_RING_SIZE];
            Arrays.sort(sorted);
            int idx = (int) Math.ceil(pct / 100.0 * n) - 1;
            if (idx < 0) idx = 0;
            if (idx >= n) idx = n - 1;
            return sorted[idx];
        }
    }

    public Map<String, Long> getToolCounts() {
        Map<String, Long> result = new java.util.LinkedHashMap<>();
        toolCounts.forEach((k, v) -> result.put(k, v.sum()));
        return result;
    }

    public double getCacheHitRate() {
        long total = cacheHits + cacheMisses;
        return total == 0 ? 0 : (double) cacheHits / total;
    }

    public int getActiveConnections() {
        return activeConnections;
    }

    /**
     * Produces a JSON-compatible map of all metrics.
     */
    public Map<String, Object> toMetricsMap() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("uptime_seconds", getUptimeSeconds());
        m.put("total_requests", getTotalRequests());
        m.put("requests_per_minute", getRequestsPerMinute());
        m.put("error_count", getErrorCount());
        m.put("rate_limited_count", getRateLimitedCount());
        m.put("avg_latency_ms", Math.round(getAvgLatencyMs() * 100.0) / 100.0);
        m.put("p50_latency_ms", getPercentileMs(50));
        m.put("p95_latency_ms", getPercentileMs(95));
        m.put("p99_latency_ms", getPercentileMs(99));
        m.put("active_connections", getActiveConnections());
        m.put("tools_called", getToolCounts());
        m.put("cache_hit_rate", Math.round(getCacheHitRate() * 10000.0) / 100.0);
        return m;
    }

    /**
     * Produces Prometheus text format.
     */
    public String toPrometheusFormat() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("# HELP burp_mcp_uptime_seconds Server uptime in seconds\n");
        sb.append("# TYPE burp_mcp_uptime_seconds gauge\n");
        sb.append("burp_mcp_uptime_seconds ").append(getUptimeSeconds()).append("\n");

        sb.append("# HELP burp_mcp_requests_total Total requests\n");
        sb.append("# TYPE burp_mcp_requests_total counter\n");
        sb.append("burp_mcp_requests_total ").append(getTotalRequests()).append("\n");

        sb.append("# HELP burp_mcp_requests_per_minute Requests in last 60s\n");
        sb.append("# TYPE burp_mcp_requests_per_minute gauge\n");
        sb.append("burp_mcp_requests_per_minute ").append(getRequestsPerMinute()).append("\n");

        sb.append("# HELP burp_mcp_errors_total Total errors\n");
        sb.append("# TYPE burp_mcp_errors_total counter\n");
        sb.append("burp_mcp_errors_total ").append(getErrorCount()).append("\n");

        sb.append("# HELP burp_mcp_rate_limited_total Total rate-limited requests\n");
        sb.append("# TYPE burp_mcp_rate_limited_total counter\n");
        sb.append("burp_mcp_rate_limited_total ").append(getRateLimitedCount()).append("\n");

        sb.append("# HELP burp_mcp_latency_p95_ms P95 latency in ms\n");
        sb.append("# TYPE burp_mcp_latency_p95_ms gauge\n");
        sb.append("burp_mcp_latency_p95_ms ").append(getPercentileMs(95)).append("\n");

        sb.append("# HELP burp_mcp_active_connections Active connections\n");
        sb.append("# TYPE burp_mcp_active_connections gauge\n");
        sb.append("burp_mcp_active_connections ").append(getActiveConnections()).append("\n");

        return sb.toString();
    }

    private void rollRateSlot() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRateSlotRoll;
        if (elapsed >= 1000) {
            int slotsToAdvance = (int) (elapsed / 1000);
            // Zero out skipped slots
            for (int i = 1; i < slotsToAdvance && i < RATE_SLOTS; i++) {
                rateSlots[(currentRateSlot + i) % RATE_SLOTS] = 0;
            }
            currentRateSlot = (currentRateSlot + slotsToAdvance) % RATE_SLOTS;
            rateSlots[currentRateSlot] = 0;
            lastRateSlotRoll = now;
        }
    }
}
