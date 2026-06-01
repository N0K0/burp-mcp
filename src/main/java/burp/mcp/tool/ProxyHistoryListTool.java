package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.proxy.ProxyHistoryFilter;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * List proxy HTTP history entries with optional filtering.
 */
public class ProxyHistoryListTool implements Tool {

    private final MontoyaApi api;

    public ProxyHistoryListTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "proxy_history_list",
                "List HTTP requests from the proxy history. Supports filtering by 'url_prefix', 'method', 'status_code'. Use 'limit' to cap results (default 500).",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("url_prefix", McpJson.property("string", "Filter results to URLs starting with this prefix"));
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
        String method = (String) args.get("method");
        final Integer statusCode;
        Object sc = args.get("status_code");
        if (sc instanceof Number) {
            statusCode = ((Number) sc).intValue();
        } else {
            statusCode = null;
        }
        int limit = 500;
        if (args.containsKey("limit") && args.get("limit") instanceof Number) {
            limit = ((Number) args.get("limit")).intValue();
            if (limit <= 0) limit = 500;
        }

        // Build a ProxyHistoryFilter for matching criteria
        ProxyHistoryFilter filter = entry -> {
            // URL prefix filter
            if (urlPrefix != null && !urlPrefix.isEmpty()) {
                String entryUrl = entry.finalRequest().url().toString();
                if (!entryUrl.startsWith(urlPrefix)) {
                    return false;
                }
            }

            // Method filter
            if (method != null && !method.isEmpty()) {
                if (!entry.finalRequest().method().equalsIgnoreCase(method)) {
                    return false;
                }
            }

            // Status code filter
            if (statusCode != null) {
                if (entry.response() == null || entry.response().statusCode() != statusCode) {
                    return false;
                }
            }

            return true;
        };

        List<ProxyHttpRequestResponse> entries = api.proxy().history(filter);
        List<Map<String, Object>> result = new ArrayList<>();

        for (ProxyHttpRequestResponse entry : entries) {
            if (result.size() >= limit) break;

            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("id", entry.id());
            item.put("url", entry.finalRequest().url().toString());
            item.put("method", entry.finalRequest().method());
            item.put("time", entry.time().toString());
            item.put("edited", entry.edited());
            item.put("listenerPort", entry.listenerPort());

            if (entry.response() != null) {
                item.put("statusCode", entry.response().statusCode());
                item.put("bodySize", entry.response().body().length());
            } else {
                item.put("statusCode", 0);
                item.put("bodySize", 0);
            }

            result.add(item);
        }

        return result;
    }
}
