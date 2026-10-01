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
 * Build an HTTP request from individual components (method, URL, headers, body).
 * Stores the result in the request cache and returns the raw request and ID.
 */
public class HttpBuildRequestTool implements Tool {

    private final MontoyaApi api;

    public HttpBuildRequestTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "http_build_request",
                "Build an HTTP request from components. Requires 'url' and optionally 'method' (default GET), 'headers' (object of key-value pairs), and 'body' (string). Returns the raw request string and a request_id for later reference.",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("url", McpJson.property("string", "Target URL (creates a GET request if used alone)"));
        props.set("method", McpJson.property("string", "HTTP method (GET, POST, PUT, DELETE, etc.)", "GET"));
        props.set("headers", McpJson.property("object", "Object of HTTP header name-value pairs to include"));
        props.set("body", McpJson.property("string", "HTTP request body content as a string", ""));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        required.add("url");
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String url = (String) args.get("url");
        if (url == null || url.isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS, "'url' is required");
        }
        String urlError = burp.mcp.util.InputValidator.validateUrl(url, "'url'");
        if (urlError != null) {
            throw new McpError(McpError.INVALID_PARAMS, urlError);
        }

        String method = (String) args.get("method");
        if (method == null || method.isEmpty()) {
            method = "GET";
        }

        // Start with a request built from the URL
        HttpRequest request = HttpRequest.httpRequestFromUrl(url);

        // Set method
        request = request.withMethod(method);

        // Add headers from the headers map
        Object headersObj = args.get("headers");
        if (headersObj instanceof Map) {
            Map<String, Object> headers = (Map<String, Object>) headersObj;
            for (Map.Entry<String, Object> entry : headers.entrySet()) {
                String name = entry.getKey();
                String value = String.valueOf(entry.getValue());
                request = request.withAddedHeader(name, value);
            }
        }

        // Set body if provided
        String body = (String) args.get("body");
        if (body != null && !body.isEmpty()) {
            request = request.withBody(body);
        }

        // Store in cache
        String rawRequest = request.toString();
        String requestId = UUID.randomUUID().toString();
        McpConfig.getRequestCache().put(requestId, rawRequest);

        // Return result
        ObjectNode result = McpJson.createObjectNode();
        result.put("raw_request", rawRequest);
        result.put("request_id", requestId);

        return result;
    }
}
