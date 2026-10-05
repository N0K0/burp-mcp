package burp.mcp.util;

import burp.api.montoya.scanner.Crawl;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds live {@link Crawl} handles by ID so agents can poll and stop
 * crawls across MCP calls. Burp's {@code Crawl.statusMessage()} is
 * unimplemented, but the request/error counters work — hence polling
 * by ID instead of one-shot status reads.
 */
public final class CrawlTracker {

    /** Cap: bounds handle retention (each entry is one live Burp task). */
    private static final int MAX_TRACKED = 100;

    private static final ConcurrentHashMap<String, Crawl> CRAWLS = new ConcurrentHashMap<>();

    private CrawlTracker() {}

    /** Store a crawl, returning its tracking ID. */
    public static String track(Crawl crawl) {
        if (CRAWLS.size() >= MAX_TRACKED) {
            throw new McpError(McpError.INVALID_PARAMS,
                    "Too many tracked crawls (" + MAX_TRACKED
                    + "); stop old ones with scanner_crawl_stop first");
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        CRAWLS.put(id, crawl);
        return id;
    }

    /** Look up a crawl or throw for unknown IDs (stopped or never existed). */
    public static Crawl get(String crawlId) {
        Crawl crawl = CRAWLS.get(crawlId);
        if (crawl == null) {
            throw new McpError(McpError.INVALID_PARAMS, "Unknown crawl_id: " + crawlId);
        }
        return crawl;
    }

    /** Drop a crawl from tracking (after delete, or on lookup failure). */
    public static void forget(String crawlId) {
        CRAWLS.remove(crawlId);
    }

    public static int trackedCount() {
        return CRAWLS.size();
    }

    public static void clear() {
        CRAWLS.clear();
    }
}
