package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Add an HTTP request/response pair to the site map.
 * Note: Burp has no Logger write API, so we use sitemap.add() instead.
 */
public class LoggerAddTool implements Tool, TargetedTool {

    private final MontoyaApi api;

    public LoggerAddTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "logger_add",
                "Send an HTTP request and add the resulting request/response pair to the Burp site map. Provide 'raw_request' as a raw HTTP request string. Note: Burp has no Logger write API, so this uses sitemap.add() to persist the entry.",
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
        // Sending needs a usable target service; derive from Host header.
        request = HttpSendRequestTool.ensureService(request, null);

        // Send the request to get a response
        HttpRequestResponse result;
        try {
            result = api.http().sendRequest(request);
        } catch (Exception e) {
            throw new McpError(McpError.REQUEST_FAILED,
                    "Failed to send HTTP request: " + e.getMessage());
        }

        // Add to site map
        try {
            api.siteMap().add(result);
        } catch (Exception e) {
            throw new McpError(McpError.INTERNAL_ERROR,
                    "Failed to add entry to site map: " + e.getMessage());
        }

        ObjectNode res = McpJson.createObjectNode();
        res.put("success", true);
        return res;
    }

    @Override
    public List<String> targetUrls(Map<String, Object> args) {
        String rawRequest = (String) args.get("raw_request");
        if (rawRequest == null || rawRequest.isEmpty()) {
            return List.of();
        }
        String url = HttpSendRequestTool.targetUrlFromRaw(rawRequest, null);
        return url != null && !url.isEmpty() ? List.of(url) : List.of();
    }
}
