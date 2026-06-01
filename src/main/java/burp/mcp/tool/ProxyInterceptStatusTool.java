package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Check if proxy intercept is currently enabled.
 */
public class ProxyInterceptStatusTool implements Tool {

    private final MontoyaApi api;

    public ProxyInterceptStatusTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "proxy_intercept_status",
                "Check whether the proxy intercept is currently enabled or disabled. Returns the current intercept state.",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        ObjectNode result = McpJson.createObjectNode();
        result.put("intercept_enabled", api.proxy().isInterceptEnabled());
        return result;
    }
}
