package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.sitemap.SiteMapFilter;
import burp.mcp.util.HttpMessageSerializer;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Filter sitemap entries by URL prefix, host, method, and/or status code.
 */
public class SitemapListFilteredTool implements Tool {

    private final MontoyaApi api;

    public SitemapListFilteredTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "sitemap_list_filtered",
                "List sitemap entries filtered by criteria. Supports 'url_prefix' for prefix matching, 'host' for exact host match, 'method' for HTTP method, 'status_code' for response status, and 'limit' to cap results (default 500).",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("url_prefix", McpJson.property("string", "Filter results to URLs starting with this prefix"));
        props.set("host", McpJson.property("string", "Filter results to this exact hostname"));
        props.set("method", McpJson.property("string", "HTTP method (GET, POST, PUT, DELETE, etc.)", "GET"));
        props.set("status_code", McpJson.property("integer", "Filter results by HTTP response status code"));
        props.set("limit", McpJson.property("integer", "Maximum number of entries to return (default: 500)", 500));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String urlPrefix = (String) args.get("url_prefix");
        String host = (String) args.get("host");
        String method = (String) args.get("method");
        Integer statusCode = null;
        Object sc = args.get("status_code");
        if (sc instanceof Number) {
            statusCode = ((Number) sc).intValue();
        }
        int limit = 500;
        if (args.containsKey("limit") && args.get("limit") instanceof Number) {
            limit = ((Number) args.get("limit")).intValue();
            if (limit <= 0) limit = 500;
        }

        List<HttpRequestResponse> entries;

        if (urlPrefix != null && !urlPrefix.isEmpty()) {
            SiteMapFilter filter = SiteMapFilter.prefixFilter(urlPrefix);
            entries = api.siteMap().requestResponses(filter);
        } else {
            entries = api.siteMap().requestResponses();
        }

        List<Map<String, Object>> result = new ArrayList<>();

        for (HttpRequestResponse entry : entries) {
            if (result.size() >= limit) break;

            // Client-side filtering for host
            if (host != null && !host.isEmpty()) {
                String entryHost = entry.request().httpService().host();
                if (!entryHost.equals(host)) {
                    continue;
                }
            }

            // Client-side filtering for method
            if (method != null && !method.isEmpty()) {
                if (!entry.request().method().equalsIgnoreCase(method)) {
                    continue;
                }
            }

            // Client-side filtering for status code
            if (statusCode != null) {
                if (entry.response() == null || entry.response().statusCode() != statusCode) {
                    continue;
                }
            }

            result.add(HttpMessageSerializer.summarizeEntry(entry));
        }

        return result;
    }
}
