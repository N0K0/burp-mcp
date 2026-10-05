package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.burpsuite.TaskExecutionEngine;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Pause or resume Burp's task execution engine (Spider, Scanner).
 * Useful to silence scan traffic during targeted manual replay.
 */
public class TaskEngineSetTool implements Tool {

    private final MontoyaApi api;

    public TaskEngineSetTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "task_engine_set",
                "Pause or resume Burp's task execution engine (Spider, Scanner). Provide 'state' as 'RUNNING' or 'PAUSED'. Returns the verified state.",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("state", McpJson.property("string", "Desired engine state: 'RUNNING' or 'PAUSED'"));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        required.add("state");
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        TaskExecutionEngine.TaskExecutionEngineState want = parseState(args.get("state"));
        try {
            api.burpSuite().taskExecutionEngine().setState(want);
            TaskExecutionEngine.TaskExecutionEngineState actual =
                    api.burpSuite().taskExecutionEngine().getState();
            ObjectNode result = McpJson.createObjectNode();
            result.put("state", String.valueOf(actual));
            result.put("success", actual == want);
            return result;
        } catch (McpError e) {
            throw e;
        } catch (Exception e) {
            throw new McpError(McpError.INTERNAL_ERROR,
                    "Failed to set task engine state: " + e.getMessage());
        }
    }

    /** Map a state argument to the engine enum. Package-visible for testing. */
    static TaskExecutionEngine.TaskExecutionEngineState parseState(Object raw) {
        if (raw instanceof String) {
            String name = ((String) raw).trim().toUpperCase(java.util.Locale.ROOT);
            if ("RUNNING".equals(name)) {
                return TaskExecutionEngine.TaskExecutionEngineState.RUNNING;
            }
            if ("PAUSED".equals(name)) {
                return TaskExecutionEngine.TaskExecutionEngineState.PAUSED;
            }
        }
        throw new McpError(McpError.INVALID_PARAMS,
                "'state' must be 'RUNNING' or 'PAUSED'");
    }
}
