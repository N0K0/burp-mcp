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
public class ScannerStartCrawlTool extends ScannerBase implements Tool {

    public ScannerStartCrawlTool(MontoyaApi api) {
        super(api);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "scanner_start_crawl",
                "Start a site crawl from seed URLs. Provide 'seed_urls' as an array of URL strings. Requires Burp Suite Professional.",
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
            throw new McpError(McpError.INTERNAL_ERROR,
                    "Failed to start crawl: " + e.getMessage());
        }

        ObjectNode result = McpJson.createObjectNode();
        result.put("statusMessage", crawl.statusMessage());
        result.put("requestCount", crawl.requestCount());
        return result;
    }
}
