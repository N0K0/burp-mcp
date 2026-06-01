package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Returns Burp Suite version, edition, and connected MCP server info.
 */
public class BurpInfoTool implements Tool {

    private final MontoyaApi api;

    public BurpInfoTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "burp_info",
                "Returns information about the Burp Suite instance: version, edition, and MCP server status.",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", McpJson.createObjectNode());
        schema.set("required", McpJson.createArrayNode());
        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        Map<String, Object> result = new LinkedHashMap<>();

        // Burp instance info
        result.put("burpVersion", api.burpSuite().version().name());
        result.put("burpEdition", api.burpSuite().version().edition().name());
        result.put("burpIsProfessional", api.burpSuite().version().edition().name().equals("PROFESSIONAL"));

        // MCP server info
        result.put("mcpServerVersion", burp.mcp.util.VersionInfo.getVersion());
        result.put("mcpServerBuildTs", burp.mcp.util.VersionInfo.getBuildTimestamp());
        result.put("mcpServerFullVersion", burp.mcp.util.VersionInfo.getFullVersion());
        result.put("montoyaApiVersion", burp.mcp.util.VersionInfo.getMontoyaApiVersion());

        // Runtime info
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("port", burp.mcp.util.McpConfig.getInstance().getPort());

        return result;
    }
}
