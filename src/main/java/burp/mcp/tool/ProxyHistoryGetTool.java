package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import burp.mcp.util.HttpMessageSerializer;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Get a specific proxy history entry by its unique ID.
 * Returns gracefully when history is empty or ID not found.
 */
public class ProxyHistoryGetTool implements Tool {

    private final MontoyaApi api;

    public ProxyHistoryGetTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("proxy_history_get",
                "Get a specific proxy history entry by its unique integer ID. "
                + "Returns a 'found' field indicating success; never throws on empty history.",
                inputSchema());
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = McpJson.createObjectNode();
        props.set("id", McpJson.property("integer", "Unique integer ID of the proxy history entry"));
        schema.set("properties", props);
        ArrayNode required = McpJson.createArrayNode();
        required.add("id");
        schema.set("required", required);
        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        int requestedId = args.get("id") instanceof Number n ? n.intValue() : -1;
        if (requestedId < 0) {
            return Map.of("found", false, "error", "'id' must be a valid non-negative integer");
        }

        List<ProxyHttpRequestResponse> entries = api.proxy().history();

        if (entries.isEmpty()) {
            return Map.of("found", false, "id", requestedId,
                    "total_entries", 0,
                    "message", "Proxy history is empty. Browse a site first.");
        }

        for (ProxyHttpRequestResponse entry : entries) {
            if (entry.id() == requestedId) {
                ObjectNode result = McpJson.createObjectNode();
                result.put("found", true);
                result.set("request", McpJson.mapper().valueToTree(
                        HttpMessageSerializer.serializeRequest(entry.finalRequest())));
                if (entry.response() != null) {
                    result.set("response", McpJson.mapper().valueToTree(
                            HttpMessageSerializer.serializeResponse(entry.response())));
                }
                result.put("id", entry.id());
                result.put("edited", entry.edited());
                result.put("time", entry.time().toString());
                result.put("listenerPort", entry.listenerPort());
                return result;
            }
        }

        return Map.of("found", false, "id", requestedId,
                "total_entries", entries.size(),
                "message", "No proxy history entry found with id=" + requestedId
                        + ". Use proxy_history_list to see available entries.");
    }
}
