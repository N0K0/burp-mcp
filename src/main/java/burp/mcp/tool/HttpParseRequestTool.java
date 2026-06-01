package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Parse an HTTP request and return its structured components.
 */
public class HttpParseRequestTool implements Tool {

    private final MontoyaApi api;

    public HttpParseRequestTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "http_parse_request",
                "Parse a raw HTTP request string and return its structured components: method, url, path, query, parameters, headers, body, contentType, and httpVersion.",
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
        required.add("raw_request");
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

        // Use the existing serializer which provides all the structured data
        Map<String, Object> result = burp.mcp.util.HttpMessageSerializer.serializeRequest(request);

        return result;
    }
}
