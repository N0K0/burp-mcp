package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Set Burp configuration options.
 */
public class ConfigSetTool implements Tool {

    private final MontoyaApi api;

    public ConfigSetTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "config_set",
                "Import Burp configuration options from JSON. Required 'config_json' (JSON string from config_get or equivalent format). Optional 'scope' (project or user, default project).",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("scope", McpJson.property("string", "Config scope: 'project' (default) or 'user'", "project"));
        props.set("config_json", McpJson.property("string", "JSON string of configuration options (from config_get or equivalent)", ""));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        required.add("config_json");
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

        String configJson = (String) args.get("config_json");
        if (configJson == null || configJson.isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS, "'config_json' is required");
        }
        try {
            McpJson.mapper().readTree(configJson);
        } catch (Exception e) {
            throw new McpError(McpError.INVALID_PARAMS,
                    "'config_json' must be valid JSON: " + e.getMessage());
        }

        try {
            if (scope.equals("project")) {
                api.burpSuite().importProjectOptionsFromJson(configJson);
            } else {
                api.burpSuite().importUserOptionsFromJson(configJson);
            }
        } catch (Exception e) {
            throw new McpError(McpError.INTERNAL_ERROR,
                    "Failed to import configuration: " + e.getMessage());
        }

        ObjectNode result = McpJson.createObjectNode();
        result.put("success", true);
        return result;
    }
}
