package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Modify an existing raw HTTP request by adding/removing headers, changing body or method.
 */
public class HttpModifyRequestTool implements Tool {

    private final MontoyaApi api;

    public HttpModifyRequestTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "http_modify_request",
                "Modify an existing HTTP request. Requires 'raw_request' and supports: 'add_headers' (object of key-value pairs to add), 'remove_headers' (array of header names to remove), 'set_body' (string to replace body), 'set_method' (string to change HTTP method).",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("raw_request", McpJson.property("string", "Full raw HTTP request string (method, headers, body)"));
        props.set("add_headers", McpJson.property("object", "Object of header name-value pairs to add to the request"));
        props.set("remove_headers", McpJson.property("array", "Array of header names to remove from the request"));
        props.set("set_body", McpJson.property("string", "New body content to replace the existing request body"));
        props.set("set_method", McpJson.property("string", "New HTTP method to replace the existing method"));
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

        HttpRequest request;
        try {
            request = HttpRequest.httpRequest(rawRequest);
        } catch (Exception e) {
            throw new McpError(McpError.INVALID_HTTP_REQUEST,
                    "Failed to parse raw HTTP request: " + e.getMessage());
        }

        // Add headers
        Object addHeaders = args.get("add_headers");
        if (addHeaders instanceof Map) {
            Map<String, Object> headers = (Map<String, Object>) addHeaders;
            for (Map.Entry<String, Object> entry : headers.entrySet()) {
                request = request.withAddedHeader(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }

        // Remove headers
        Object removeHeaders = args.get("remove_headers");
        if (removeHeaders instanceof java.util.List) {
            for (Object headerName : (java.util.List<?>) removeHeaders) {
                request = request.withRemovedHeader(String.valueOf(headerName));
            }
        }

        // Set body
        String body = (String) args.get("set_body");
        if (body != null) {
            request = request.withBody(body);
        }

        // Set method
        String method = (String) args.get("set_method");
        if (method != null && !method.isEmpty()) {
            request = request.withMethod(method);
        }

        // Return the modified request
        ObjectNode result = McpJson.createObjectNode();
        result.put("raw_request", request.toString());
        return result;
    }
}
