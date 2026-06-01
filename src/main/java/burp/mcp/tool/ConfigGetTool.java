package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Get Burp configuration options.
 */
public class ConfigGetTool implements Tool {

    private final MontoyaApi api;

    public ConfigGetTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "config_get",
                "Export Burp configuration options as JSON. Optional 'scope' (project or user, default project) and 'paths' (array of specific config paths to export; if omitted, exports all options).",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("scope", McpJson.property("string", "Config scope: 'project' (default) or 'user'", "project"));
        props.set("paths", McpJson.property("array", "Array of specific config paths to export (omit to export all)"));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String scope = (String) args.get("scope");
        if (scope == null || scope.isEmpty()) {
            scope = "project";
        }

        if (!scope.equals("project") && !scope.equals("user")) {
            throw new McpError(McpError.INVALID_PARAMS,
                    "'scope' must be one of: project, user");
        }

        // Get specific paths or export all
        String[] paths = null;
        Object pathsObj = args.get("paths");
        if (pathsObj instanceof java.util.List) {
            java.util.List<?> pathList = (java.util.List<?>) pathsObj;
            paths = pathList.toArray(new String[0]);
        }

        String configJson;
        try {
            if (scope.equals("project")) {
                configJson = paths != null
                        ? api.burpSuite().exportProjectOptionsAsJson(paths)
                        : api.burpSuite().exportProjectOptionsAsJson();
            } else {
                configJson = paths != null
                        ? api.burpSuite().exportUserOptionsAsJson(paths)
                        : api.burpSuite().exportUserOptionsAsJson();
            }
        } catch (Exception e) {
            throw new McpError(McpError.INTERNAL_ERROR,
                    "Failed to export configuration: " + e.getMessage());
        }

        ObjectNode result = McpJson.createObjectNode();
        result.put("config", configJson);
        result.put("scope", scope);
        return result;
    }
}
