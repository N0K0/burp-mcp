package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.mcp.util.McpConfig;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Retrieve a previously stored HTTP request from the cache by its request_id.
 */
public class HttpGetRequestTool implements Tool {

    private final MontoyaApi api;

    public HttpGetRequestTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "http_get_request",
                "Retrieve a previously stored HTTP request from the cache using its request_id. The request must have been stored using http_store_request or http_build_request.",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("request_id", McpJson.property("string", "Unique ID of a previously stored request (from http_store_request or http_build_request)"));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        required.add("request_id");
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String requestId = (String) args.get("request_id");
        if (requestId == null || requestId.isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS, "'request_id' is required");
        }

        String rawRequest = McpConfig.getRequestCache().get(requestId);
        if (rawRequest == null) {
            throw new McpError(McpError.NOT_FOUND,
                    "No request found with id: " + requestId + " (may have expired)");
        }

        // Return the raw request
        ObjectNode result = McpJson.createObjectNode();
        result.put("raw_request", rawRequest);
        return result;
    }
}
