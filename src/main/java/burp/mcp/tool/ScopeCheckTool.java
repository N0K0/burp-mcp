package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Check whether a given URL is in Burp's scope.
 */
public class ScopeCheckTool implements Tool {

    private final MontoyaApi api;

    public ScopeCheckTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "scope_check",
                "Check whether a given URL is included in Burp's target scope. Returns true if the URL is in scope, false otherwise.",
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

        // scope.isInScope() takes a String (URL), not HttpRequest
        boolean inScope = api.scope().isInScope(url);

        ObjectNode result = McpJson.createObjectNode();
        result.put("in_scope", inScope);
        return result;
    }
}
