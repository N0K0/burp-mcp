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
                "Returns information about the Burp Suite instance: version, edition, current project (name/id), and MCP server status. Note: Burp projects cannot be created via the API — launch Burp with --project-file=<path> for a fresh project.",
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

        // Current project (read-only in Montoya — no create/open API)
        try {
            if (api.project() != null) {
                result.put("projectName", api.project().name());
                result.put("projectId", api.project().id());
            }
        } catch (Exception ignored) {
            // Project unavailable (e.g. mocked API in tests) — omit fields
        }

        // MCP server info
        result.put("mcpServerVersion", burp.mcp.util.VersionInfo.getVersion());
        result.put("mcpServerBuildTs", burp.mcp.util.VersionInfo.getBuildTimestamp());
        result.put("mcpServerFullVersion", burp.mcp.util.VersionInfo.getFullVersion());
        result.put("montoyaApiVersion", burp.mcp.util.VersionInfo.getMontoyaApiVersion());

        // Runtime info
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("port", burp.mcp.util.McpConfig.getInstance().getPort());
        String socketPath = burp.mcp.util.McpConfig.getActiveSocketPath();
        if (socketPath != null) {
            result.put("mcpSocketPath", socketPath);
        }

        // Access-control context so agents can understand gate outcomes
        try {
            burp.mcp.util.McpConfig cfg = burp.mcp.util.McpConfig.getInstance();
            result.put("scopeEnforcement", cfg.getScopeEnforcement());
            result.put("approvalWaitSeconds", cfg.getApprovalWaitSeconds());
            result.put("approvalTtlSeconds", cfg.getApprovalTtlSeconds());
        } catch (Exception ignored) {
            // Config unavailable (mock/startup) — omit
        }
        try {
            burp.mcp.util.ApprovalManager approvals = burp.mcp.util.McpConfig.getApprovalManager();
            if (approvals != null) {
                result.put("pendingApprovals", approvals.pendingCount());
                result.put("sessionGrants", Map.of(
                        "tools", new java.util.ArrayList<>(approvals.getToolGrants()),
                        "origins", new java.util.ArrayList<>(approvals.getOriginGrants())));
            }
        } catch (Exception ignored) {
            // Approval manager unavailable — omit
        }

        return result;
    }
}
