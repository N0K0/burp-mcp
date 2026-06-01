package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.mcp.util.McpConfig;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.UUID;

/**
 * Store a raw HTTP request in the shared cache for later retrieval.
 */
public class HttpStoreRequestTool implements Tool {

    private final MontoyaApi api;

    public HttpStoreRequestTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "http_store_request",
                "Store a raw HTTP request string in the cache. Returns a request_id that can be used with http_get_request to retrieve the stored request.",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("raw_request", McpJson.property("string", "Full raw HTTP request string (method, headers, body)"));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String rawRequest = (String) args.get("raw_request");
        if (rawRequest == null || rawRequest.isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS, "'raw_request' is required");
        }

        // Validate that it's a parseable HTTP request
        try {
            HttpRequest.httpRequest(rawRequest);
        } catch (Exception e) {
            throw new McpError(McpError.INVALID_HTTP_REQUEST,
                    "Failed to parse raw HTTP request: " + e.getMessage());
        }

        // Generate a unique ID and store
        String requestId = UUID.randomUUID().toString();
        McpConfig.getRequestCache().put(requestId, rawRequest);

        // Return the ID
        ObjectNode result = McpJson.createObjectNode();
        result.put("request_id", requestId);
        return result;
    }
}
