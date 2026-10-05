package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.burpsuite.TaskExecutionEngine;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Report Burp's task execution engine state (Spider/Scanner running or paused).
 */
public class TaskEngineStatusTool implements Tool {

    private final MontoyaApi api;

    public TaskEngineStatusTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "task_engine_status",
                "Report whether Burp's task execution engine (Spider, Scanner) is RUNNING or PAUSED.",
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
        ObjectNode result = McpJson.createObjectNode();
        try {
            TaskExecutionEngine.TaskExecutionEngineState state =
                    api.burpSuite().taskExecutionEngine().getState();
            result.put("state", String.valueOf(state));
        } catch (Exception e) {
            throw new McpError(McpError.INTERNAL_ERROR,
                    "Failed to read task engine state: " + e.getMessage());
        }
        return result;
    }
}
