package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.mcp.util.HttpMessageSerializer;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Send multiple HTTP requests in parallel and return their responses.
 */
public class HttpSendRequestsTool implements Tool {

    private final MontoyaApi api;

    public HttpSendRequestsTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "http_send_requests",
                "Send multiple HTTP requests in parallel and return their responses. Accepts a list of raw HTTP request strings and sends them concurrently.",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("requests", McpJson.property("array", "Array of raw HTTP request strings to send in parallel"));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        required.add("requests");
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        Object requestsObj = args.get("requests");
        if (requestsObj == null || !(requestsObj instanceof List)) {
            throw new McpError(McpError.INVALID_PARAMS,
                    "'requests' must be a non-empty array of raw HTTP request strings");
        }

        List<?> requestList = (List<?>) requestsObj;
        if (requestList.isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS,
                    "'requests' array must not be empty");
        }

        List<HttpRequest> requests = new ArrayList<>();
        for (int i = 0; i < requestList.size(); i++) {
            String rawRequest = String.valueOf(requestList.get(i));
            try {
                requests.add(HttpRequest.httpRequest(rawRequest));
            } catch (Exception e) {
                throw new McpError(McpError.INVALID_HTTP_REQUEST,
                        "Failed to parse request at index " + i + ": " + e.getMessage());
            }
        }

        try {
            List<HttpRequestResponse> responses = api.http().sendRequests(requests);
            List<Map<String, Object>> result = new ArrayList<>();
            for (HttpRequestResponse pair : responses) {
                result.add(HttpMessageSerializer.serializeResponse(pair.response()));
            }
            return result;
        } catch (McpError e) {
            throw e;
        } catch (Exception e) {
            throw new McpError(McpError.REQUEST_FAILED,
                    "Failed to send HTTP requests: " + e.getMessage());
        }
    }
}
