package burp.mcp.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Common interface for all MCP tools.
 */
public interface Tool {

    /**
     * Returns the tool's metadata (name, description, input schema).
     */
    ToolDefinition definition();

    /**
     * Execute the tool with the given arguments.
     * @param args The tool arguments as a Map
     * @return The tool result as a Map (will be serialized to JSON)
     */
    Object execute(Map<String, Object> args);

    /**
     * The JSON schema for the tool's input parameters.
     */
    ObjectNode inputSchema();
}
