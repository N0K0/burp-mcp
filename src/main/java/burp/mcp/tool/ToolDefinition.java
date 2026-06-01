package burp.mcp.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Metadata for an MCP tool: name, description, and input schema.
 */
public class ToolDefinition {

    private final String name;
    private final String description;
    private final ObjectNode inputSchema;

    public ToolDefinition(String name, String description, ObjectNode inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public ObjectNode inputSchema() {
        return inputSchema;
    }

    @Override
    public String toString() {
        return "ToolDefinition{name='" + name + "', description='" + description + "'}";
    }
}
