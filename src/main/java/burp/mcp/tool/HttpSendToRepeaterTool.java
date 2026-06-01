package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Send an HTTP request to the Repeater tool.
 */
public class HttpSendToRepeaterTool implements Tool {

    private final MontoyaApi api;

    public HttpSendToRepeaterTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "http_send_to_repeater",
                "Send an HTTP request to Burp Repeater for manual analysis. Provide 'raw_request' (raw HTTP request string) and optionally 'tab_name' to label the Repeater tab.",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("raw_request", McpJson.property("string", "Full raw HTTP request string (method, headers, body)"));
        props.set("tab_name", McpJson.property("string", "Optional label for the Repeater tab"));
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

        String tabName = (String) args.get("tab_name");
        if (tabName == null || tabName.isEmpty()) {
            tabName = "";
        }

        try {
            api.repeater().sendToRepeater(request, tabName);
        } catch (Exception e) {
            throw new McpError(McpError.INTERNAL_ERROR,
                    "Failed to send request to Repeater: " + e.getMessage());
        }

        ObjectNode result = McpJson.createObjectNode();
        result.put("success", true);
        result.put("tab_name", tabName);
        return result;
    }
}
