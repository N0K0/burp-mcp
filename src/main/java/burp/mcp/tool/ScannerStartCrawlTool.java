package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.scanner.Crawl;
import burp.api.montoya.scanner.CrawlConfiguration;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Start a site crawl.
 */
public class ScannerStartCrawlTool extends ScannerBase implements Tool, TargetedTool {

    public ScannerStartCrawlTool(MontoyaApi api) {
        super(api);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "scanner_start_crawl",
                "Start a site crawl from seed URLs. Provide 'seed_urls' as an array of URL strings. Returns a crawl_id for tracking. Requires Burp Suite Professional. NOTE: current Burp versions run the crawl but its statusMessage() is unimplemented — poll progress with scanner_crawl_status (request/error counts) and stop with scanner_crawl_stop.",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("seed_urls", McpJson.property("array", "Array of URL strings to start crawling from"));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        required.add("seed_urls");
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        checkProEdition();

        Object urlsObj = args.get("seed_urls");
        if (!(urlsObj instanceof List)) {
            throw new McpError(McpError.INVALID_PARAMS, "'seed_urls' must be an array of URL strings");
        }

        List<?> urlsList = (List<?>) urlsObj;
        if (urlsList.isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS, "'seed_urls' must not be empty");
        }

        // Convert URL strings for crawlConfiguration (takes String... not HttpRequest[])
        String[] seedUrls = new String[urlsList.size()];
        int idx = 0;
        for (Object urlObj : urlsList) {
            if (!(urlObj instanceof String)) {
                throw new McpError(McpError.INVALID_PARAMS, "All items in 'seed_urls' must be strings");
            }
            String url = (String) urlObj;
            if (url.isEmpty()) {
                throw new McpError(McpError.INVALID_PARAMS, "URL must not be empty");
            }
            String urlError = burp.mcp.util.InputValidator.validateUrl(url, "'seed_urls'");
            if (urlError != null) {
                throw new McpError(McpError.INVALID_PARAMS, urlError);
            }
            seedUrls[idx++] = url;
        }

        // Start crawl
        Crawl crawl;
        try {
            CrawlConfiguration crawlConfig = CrawlConfiguration.crawlConfiguration(seedUrls);
            crawl = api.scanner().startCrawl(crawlConfig);
        } catch (Exception e) {
            throw crawlError(e);
        }

        // Burp runs the crawl but its statusMessage() is unimplemented,
        // so degrade the status fields instead of masking a live task.
        ObjectNode result = McpJson.createObjectNode();
        result.put("crawl_id", burp.mcp.util.CrawlTracker.track(crawl));
        try {
            result.put("statusMessage", crawl.statusMessage());
            result.put("requestCount", crawl.requestCount());
        } catch (Exception e) {
            result.put("started", true);
            result.put("status_unavailable",
                    "Crawl status text is not implemented by this Burp version: " + e.getMessage()
                    + ". Poll scanner_crawl_status for request/error counts.");
        }
        return result;
    }

    @Override
    public List<String> targetUrls(Map<String, Object> args) {
        List<String> targets = new ArrayList<>();
        Object urlsObj = args.get("seed_urls");
        if (urlsObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s && !s.isEmpty()) {
                    targets.add(s);
                }
            }
        }
        return targets;
    }

    private McpError crawlError(Exception e) {
        String msg = String.valueOf(e.getMessage());
        if (msg.toLowerCase(java.util.Locale.ROOT).contains("not yet implemented")) {
            String burpVersion = "unknown";
            try {
                burpVersion = api.burpSuite().version().name();
            } catch (Exception ignored) {
            }
            return new McpError(McpError.INTERNAL_ERROR,
                    "Burp Suite (" + burpVersion + ") has not implemented crawl-only tasks via the API"
                    + " (upstream Montoya docs still mark Crawl as 'not yet implemented')."
                    + " For discovery use scanner_start_audit on seed URLs, or map via proxy traffic.");
        }
        return new McpError(McpError.INTERNAL_ERROR,
                "Failed to start crawl: " + msg);
    }
}
