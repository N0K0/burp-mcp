package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.RedirectionMode;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.mcp.util.HttpMessageSerializer;
import burp.mcp.util.InputValidator;
import burp.mcp.util.McpConfig;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Send a single HTTP request and return the response.
 * Supports sending by URL or by raw HTTP request string.
 */
public class HttpSendRequestTool implements Tool {

    private final MontoyaApi api;

    public HttpSendRequestTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "http_send_request",
                "Send an HTTP request and return the response. Provide either 'url' to create a GET request from a URL, or 'raw_request' to send a full raw HTTP request string. Optional parameters: 'follow_redirects' (boolean, default true) and 'timeout_ms' (integer, default from config).",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("url", McpJson.property("string", "Target URL (creates a GET request if used alone)"));
        props.set("raw_request", McpJson.property("string", "Full raw HTTP request string (method, headers, body)"));
        props.set("follow_redirects", McpJson.property("boolean", "Whether to follow HTTP redirects (default: true)", true));
        props.set("timeout_ms", McpJson.property("integer", "Request timeout in milliseconds (default: config value)"));
        schema.set("properties", props);

        ArrayNode req = McpJson.createArrayNode();
        schema.set("required", req);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String rawRequest = (String) args.get("raw_request");
        String url = (String) args.get("url");

        HttpRequest request;
        if (rawRequest != null && !rawRequest.isEmpty()) {
            try {
                request = HttpRequest.httpRequest(rawRequest);
            } catch (Exception e) {
                throw new McpError(McpError.INVALID_HTTP_REQUEST,
                        "Failed to parse raw HTTP request: " + e.getMessage());
            }
        } else if (url != null && !url.isEmpty()) {
            String urlError = InputValidator.validateUrl(url, "'url'");
            if (urlError != null) {
                throw new McpError(McpError.INVALID_PARAMS, urlError);
            }
            request = HttpRequest.httpRequestFromUrl(url);
        } else {
            throw new McpError(McpError.INVALID_PARAMS,
                    "Either 'url' or 'raw_request' must be provided");
        }

        // Build request options
        RequestOptions options;
        try {
            options = RequestOptions.requestOptions();
        } catch (Exception e) {
            throw new McpError(McpError.INTERNAL_ERROR,
                    "Failed to create request options: " + e.getMessage());
        }

        // Handle follow_redirects
        Boolean followRedirects = (Boolean) args.get("follow_redirects");
        if (followRedirects != null && !followRedirects) {
            options = options.withRedirectionMode(RedirectionMode.NEVER);
        }

        // Handle timeout (clamped to the configured valid range)
        Object timeoutObj = args.get("timeout_ms");
        if (timeoutObj instanceof Number) {
            long timeoutMs = ((Number) timeoutObj).longValue();
            if (timeoutMs < 1000) timeoutMs = 1000;
            if (timeoutMs > 300_000) timeoutMs = 300_000;
            options = options.withResponseTimeout(timeoutMs);
        } else {
            long defaultTimeout = McpConfig.getInstance().getRequestTimeoutMs();
            options = options.withResponseTimeout(defaultTimeout);
        }

        // Send request
        try {
            var response = api.http().sendRequest(request, options);
            return HttpMessageSerializer.serializeResponse(response.response());
        } catch (McpError e) {
            throw e;
        } catch (Exception e) {
            throw new McpError(McpError.REQUEST_FAILED,
                    "Failed to send HTTP request: " + e.getMessage());
        }
    }
}
