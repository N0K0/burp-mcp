package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Add or remove a URL from Burp's target scope.
 */
public class ScopeSetTool implements Tool {

    private final MontoyaApi api;

    public ScopeSetTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "scope_set",
                "Add or remove a URL from Burp's target scope. Use 'action' set to 'include' to add the URL to scope, or 'exclude' to remove it. Verifies the change and returns success status.",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("url", McpJson.property("string", "Target URL (creates a GET request if used alone)"));
        props.set("action", McpJson.property("string", "Scope action: 'include' to add to scope, 'exclude' to remove from scope"));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        required.add("url");
        required.add("action");
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String url = (String) args.get("url");
        String action = (String) args.get("action");

        if (url == null || url.isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS, "'url' is required");
        }
        String urlError = burp.mcp.util.InputValidator.validateUrl(url, "'url'");
        if (urlError != null) {
            throw new McpError(McpError.INVALID_PARAMS, urlError);
        }
        if (action == null || action.isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS, "'action' is required");
        }

        // scope methods take String (URL), not HttpRequest
        if ("include".equalsIgnoreCase(action)) {
            api.scope().includeInScope(url);
            boolean verified = api.scope().isInScope(url);
            ObjectNode result = McpJson.createObjectNode();
            result.put("success", verified);
            result.put("url", url);
            result.put("action", "include");
            return result;
        } else if ("exclude".equalsIgnoreCase(action)) {
            api.scope().excludeFromScope(url);
            boolean verified = !api.scope().isInScope(url);
            ObjectNode result = McpJson.createObjectNode();
            result.put("success", verified);
            result.put("url", url);
            result.put("action", "exclude");
            return result;
        } else {
            throw new McpError(McpError.INVALID_PARAMS,
                    "'action' must be 'include' or 'exclude', got: " + action);
        }
    }
}
