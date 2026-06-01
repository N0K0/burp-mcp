package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.mcp.util.HttpMessageSerializer;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * List sitemap entries with optional filtering by scope and limit.
 */
public class SitemapListTool implements Tool {

    private final MontoyaApi api;

    public SitemapListTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "sitemap_list",
                "List all entries in the site map. Use 'limit' to cap the number of results (default 500). Set 'in_scope_only' to true to return only in-scope entries.",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("limit", McpJson.property("integer", "Maximum number of entries to return (default: 500)", 500));
        props.set("in_scope_only", McpJson.property("boolean", "If true, return only URLs within the target scope", false));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        int limit = 500;
        boolean inScopeOnly = false;

        if (args.containsKey("limit") && args.get("limit") instanceof Number) {
            limit = ((Number) args.get("limit")).intValue();
            if (limit <= 0) limit = 500;
        }
        if (args.containsKey("in_scope_only") && args.get("in_scope_only") instanceof Boolean) {
            inScopeOnly = (Boolean) args.get("in_scope_only");
        }

        List<HttpRequestResponse> entries = api.siteMap().requestResponses();
        List<Map<String, Object>> result = new ArrayList<>();

        for (HttpRequestResponse entry : entries) {
            if (result.size() >= limit) break;

            if (inScopeOnly && !entry.request().isInScope()) {
                continue;
            }

            result.add(HttpMessageSerializer.summarizeEntry(entry));
        }

        return result;
    }
}
