package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Toggle proxy intercept on/off.
 */
public class ProxyToggleInterceptTool implements Tool {

    private final MontoyaApi api;

    public ProxyToggleInterceptTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "proxy_toggle_intercept",
                "Toggle the proxy intercept mode. If intercept is currently enabled, this will disable it, and vice versa. Returns the new intercept state.",
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
        boolean isEnabled = api.proxy().isInterceptEnabled();

        if (isEnabled) {
            api.proxy().disableIntercept();
        } else {
            api.proxy().enableIntercept();
        }

        ObjectNode result = McpJson.createObjectNode();
        result.put("intercept_enabled", !isEnabled);
        return result;
    }
}
