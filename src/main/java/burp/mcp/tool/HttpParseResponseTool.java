package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Parse an HTTP response and return its structured components.
 */
public class HttpParseResponseTool implements Tool {

    private final MontoyaApi api;

    public HttpParseResponseTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "http_parse_response",
                "Parse a raw HTTP response string and return its structured components: statusCode, status_text, headers, body, mimeType, and cookies.",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("raw_response", McpJson.property("string", "Full raw HTTP response string to parse"));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        required.add("raw_response");
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String rawResponse = (String) args.get("raw_response");
        if (rawResponse == null || rawResponse.isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS, "'raw_response' is required");
        }

        HttpResponse response;
        try {
            response = HttpResponse.httpResponse(rawResponse);
        } catch (Exception e) {
            throw new McpError(McpError.INVALID_HTTP_REQUEST,
                    "Failed to parse raw HTTP response: " + e.getMessage());
        }

        // Use the existing serializer which provides all the structured data
        Map<String, Object> result = burp.mcp.util.HttpMessageSerializer.serializeResponse(response);

        return result;
    }
}
