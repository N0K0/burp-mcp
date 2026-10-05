package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.scanner.Crawl;
import burp.mcp.util.CrawlTracker;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Poll progress of a crawl started with scanner_start_crawl.
 * Uses the request/error counters (implemented) rather than the
 * status text (unimplemented by Burp).
 */
public class ScannerCrawlStatusTool extends ScannerBase implements Tool {

    public ScannerCrawlStatusTool(MontoyaApi api) {
        super(api);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "scanner_crawl_status",
                "Poll progress of a crawl by crawl_id (from scanner_start_crawl). Returns request and error counts. Requires Burp Suite Professional.",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("crawl_id", McpJson.property("string", "Crawl tracking ID from scanner_start_crawl"));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        required.add("crawl_id");
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        checkProEdition();

        Object idObj = args.get("crawl_id");
        if (!(idObj instanceof String) || ((String) idObj).isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS, "'crawl_id' is required");
        }
        String crawlId = (String) idObj;
        Crawl crawl = CrawlTracker.get(crawlId);

        ObjectNode result = McpJson.createObjectNode();
        result.put("crawl_id", crawlId);
        try {
            result.put("requestCount", crawl.requestCount());
            result.put("errorCount", crawl.errorCount());
        } catch (Exception e) {
            throw new McpError(McpError.INTERNAL_ERROR,
                    "Crawl counters unreadable on this Burp version: " + e.getMessage());
        }
        try {
            result.put("statusMessage", crawl.statusMessage());
        } catch (Exception e) {
            result.put("statusMessage", "unavailable (not implemented by Burp)");
        }
        return result;
    }
}
