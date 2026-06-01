package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.mcp.util.HttpMessageSerializer;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Get a specific sitemap entry by URL. Returns the full request and response.
 */
public class SitemapGetTool implements Tool {

    private final MontoyaApi api;

    public SitemapGetTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "sitemap_get",
                "Get a specific sitemap entry by URL. Returns the full request and response details. The 'url' parameter supports exact match or prefix match (first matching entry returned).",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("url", McpJson.property("string", "Target URL (creates a GET request if used alone)"));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        required.add("url");
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String url = (String) args.get("url");
        if (url == null || url.isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS, "'url' is required");
        }

        List<HttpRequestResponse> entries = api.siteMap().requestResponses();

        // Try exact match first
        for (HttpRequestResponse entry : entries) {
            if (entry.request().url().toString().equals(url)) {
                return HttpMessageSerializer.serializeHttpRequestResponse(entry);
            }
        }

        // Fall back to prefix match
        for (HttpRequestResponse entry : entries) {
            if (entry.request().url().toString().startsWith(url)) {
                return HttpMessageSerializer.serializeHttpRequestResponse(entry);
            }
        }

        throw new McpError(McpError.NOT_FOUND, "No sitemap entry found matching URL: " + url);
    }
}
