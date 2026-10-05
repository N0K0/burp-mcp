package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpMode;
import burp.api.montoya.http.HttpService;
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

import java.util.List;
import java.util.Map;

/**
 * Send a single HTTP request and return the response.
 * Supports sending by URL or by raw HTTP request string.
 */
public class HttpSendRequestTool implements Tool, TargetedTool {

    private final MontoyaApi api;

    public HttpSendRequestTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "http_send_request",
                "Send an HTTP request and return the response. Provide either 'url' to create a GET request from a URL, or 'raw_request' to send a full raw HTTP request string (must contain a Host header unless 'url' is also given to derive the target service). Optional parameters: 'follow_redirects' (boolean, default true), 'timeout_ms' (integer, default from config), and 'http_mode' ('auto' default, 'http1', 'http2', 'http2-no-alpn'). NOTE: a non-auto http_mode uses Burp's defaults for redirects/timeout (follow_redirects/timeout_ms are ignored).",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("url", McpJson.property("string", "Target URL (creates a GET request if used alone); the URL's scheme always sets the service, so https stays https"));
        props.set("raw_request", McpJson.property("string", "Full raw HTTP/1.x request message, headers terminated by a blank line. Origin-form needs a Host header (http assumed unless host:443); absolute-form targets (GET https://host/path ...) set scheme + service directly"));
        props.set("follow_redirects", McpJson.property("boolean", "Whether to follow HTTP redirects (default: true; ignored unless http_mode is 'auto')", true));
        props.set("timeout_ms", McpJson.property("integer", "Request timeout in milliseconds (default: config value; ignored unless http_mode is 'auto')"));
        props.set("http_mode", McpJson.property("string", "HTTP protocol mode: 'auto' (default), 'http1', 'http2', 'http2-no-alpn'. Non-auto uses Burp's default redirect/timeout handling", "auto"));
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

        // Burp refuses to send a request with no HTTP service. Raw requests
        // (and some Burp versions' from-URL factory) can come back serviceless,
        // so always resolve and attach one deterministically.
        request = ensureService(request, url);

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

        // Send request (explicit protocol mode bypasses options — no combined overload exists)
        HttpMode mode = parseHttpMode(args.get("http_mode"));
        try {
            var response = mode == HttpMode.AUTO
                    ? api.http().sendRequest(request, options)
                    : api.http().sendRequest(request, mode);
            return HttpMessageSerializer.serializeResponse(response.response());
        } catch (McpError e) {
            throw e;
        } catch (Exception e) {
            throw new McpError(McpError.REQUEST_FAILED,
                    "Failed to send HTTP request: " + e.getMessage());
        }
    }

    @Override
    public List<String> targetUrls(Map<String, Object> args) {
        try {
            String rawRequest = (String) args.get("raw_request");
            String url = (String) args.get("url");
            if (rawRequest != null && !rawRequest.isEmpty()) {
                String resolved = targetUrlFromRaw(rawRequest, url);
                return resolved != null ? List.of(resolved) : List.of();
            }
            if (url != null && !url.isEmpty()) {
                return List.of(url);
            }
        } catch (Exception ignored) {
            // Malformed arguments are the tool's job to report on execution.
        }
        return List.of();
    }

    /**
     * Derive a target URL from a raw HTTP request without Montoya factories
     * (unit-testable). Absolute-form targets win; otherwise the Host header
     * supplies the authority. Returns null when no target can be derived.
     */
    static String targetUrlFromRaw(String rawRequest, String urlHint) {
        if (urlHint != null && !urlHint.isEmpty()) {
            return urlHint;
        }
        if (rawRequest == null || rawRequest.isEmpty()) {
            return null;
        }
        String[] lines = rawRequest.replace("\r\n", "\n").split("\n");
        if (lines.length == 0) {
            return null;
        }
        String[] requestLine = lines[0].trim().split("\\s+");
        if (requestLine.length < 2) {
            return null;
        }
        String target = requestLine[1].trim();
        if (target.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            return target;
        }
        String host = null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                break; // end of headers
            }
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Host")) {
                host = line.substring(colon + 1).trim();
                break;
            }
        }
        if (host == null || host.isEmpty()) {
            return null;
        }
        ServiceParts parts;
        try {
            parts = partsFromHostHeader(host);
        } catch (McpError e) {
            return null;
        }
        String scheme = parts.secure() ? "https" : "http";
        boolean defaultPort = (parts.secure() && parts.port() == 443)
                || (!parts.secure() && parts.port() == 80);
        String hostPart = parts.host().contains(":") ? "[" + parts.host() + "]" : parts.host();
        String path = target.isEmpty() ? "/" : target;
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return scheme + "://" + hostPart + (defaultPort ? "" : ":" + parts.port()) + path;
    }

    /**
     * Map an http_mode argument to Montoya HttpMode. Package-visible for testing.
     */
    static HttpMode parseHttpMode(Object raw) {        if (raw == null) {
            return HttpMode.AUTO;
        }
        if (!(raw instanceof String)) {
            throw new McpError(McpError.INVALID_PARAMS,
                    "'http_mode' must be one of: auto, http1, http2, http2-no-alpn");
        }
        if (((String) raw).isEmpty()) {
            return HttpMode.AUTO;
        }
        String name = ((String) raw).trim().toLowerCase(java.util.Locale.ROOT);
        return switch (name) {
            case "auto" -> HttpMode.AUTO;
            case "http1", "http/1", "http/1.1" -> HttpMode.HTTP_1;
            case "http2", "http/2" -> HttpMode.HTTP_2;
            case "http2-no-alpn", "http2_noalpn" -> HttpMode.HTTP_2_IGNORE_ALPN;
            default -> throw new McpError(McpError.INVALID_PARAMS,
                    "'http_mode' must be one of: auto, http1, http2, http2-no-alpn");
        };
    }

    /**
     * Guarantee the request carries a correct HTTP service. Factory-provided
     * services are not trusted: observed in the wild is
     * {@code httpRequestFromUrl("https://…")} yielding a plaintext port-80
     * service, which sends https URLs out over HTTP. Authority order:
     * explicit URL &gt; absolute-form request target &gt; Host header &gt;
     * factory service (only if it carries a usable host).
     */
    static HttpRequest ensureService(HttpRequest request, String urlHint) {
        // 1. Explicit URL always wins.
        if (urlHint != null && !urlHint.isEmpty()) {
            return request.withService(serviceFromUrl(urlHint));
        }
        // 2. Absolute-form request target carries scheme + authority.
        String target = requestTarget(request);
        if (target != null && target.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            try {
                return request.withService(serviceFromUrl(target));
            } catch (McpError ignored) {
                // Fall through to Host header.
            }
        }
        // 3. Host header (explicit wire authority beats opaque factory state).
        String host = null;
        try {
            host = request.headerValue("Host");
        } catch (Exception ignored) {
        }
        if (host != null && !host.isEmpty()) {
            return request.withService(serviceFromHostHeader(host));
        }
        // 4. Factory service, only if it carries a usable host.
        try {
            if (request.httpService() != null
                    && request.httpService().host() != null
                    && !request.httpService().host().isEmpty()) {
                return request;
            }
        } catch (Exception ignored) {
        }
        throw new McpError(McpError.INVALID_PARAMS,
                "Request has no HTTP service and no Host header; provide 'url' to derive the target service");
    }

    /** First request-target token of the raw message, or null. */
    static String requestTarget(HttpRequest request) {
        try {
            String raw = request.toString();
            int eol = raw.indexOf("\r\n");
            String firstLine = eol >= 0 ? raw.substring(0, eol) : raw;
            String[] parts = firstLine.split(" ");
            return parts.length >= 2 ? parts[1].trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Pure host/port/scheme triple — no Burp runtime needed (unit-testable). */
    record ServiceParts(String host, int port, boolean secure) {
    }

    /** Build a service from an absolute http(s) URL. */
    static HttpService serviceFromUrl(String url) {
        ServiceParts parts = partsFromUrl(url);
        return HttpService.httpService(parts.host(), parts.port(), parts.secure());
    }

    /** Build a service from a Host header value (host or host:port, incl. IPv6). */
    static HttpService serviceFromHostHeader(String hostHeader) {
        ServiceParts parts = partsFromHostHeader(hostHeader);
        return HttpService.httpService(parts.host(), parts.port(), parts.secure());
    }

    /** Parse an absolute http(s) URL into service parts. */
    static ServiceParts partsFromUrl(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                throw new McpError(McpError.INVALID_PARAMS,
                        "Cannot derive HTTP service from URL: " + url);
            }
            boolean secure = "https".equalsIgnoreCase(uri.getScheme());
            int port = uri.getPort();
            if (port == -1) {
                port = secure ? 443 : 80;
            }
            return new ServiceParts(host, port, secure);
        } catch (java.net.URISyntaxException e) {
            throw new McpError(McpError.INVALID_PARAMS,
                    "Cannot derive HTTP service from URL: " + url);
        }
    }

    /** Parse a Host header value into service parts (443 implies https). */
    static ServiceParts partsFromHostHeader(String hostHeader) {
        String host = hostHeader.trim();
        int port = -1;
        if (host.startsWith("[")) {
            // [v6-literal] or [v6-literal]:port
            int close = host.indexOf(']');
            if (close < 0) {
                throw new McpError(McpError.INVALID_PARAMS,
                        "Cannot derive HTTP service from Host header: " + hostHeader);
            }
            String after = host.substring(close + 1);
            host = host.substring(1, close);
            if (after.startsWith(":")) {
                port = parsePort(after.substring(1), hostHeader);
            }
        } else {
            int firstColon = host.indexOf(':');
            int lastColon = host.lastIndexOf(':');
            if (firstColon >= 0 && firstColon == lastColon) {
                port = parsePort(host.substring(firstColon + 1), hostHeader);
                host = host.substring(0, firstColon);
            }
            // Multiple colons without brackets: bare IPv6 literal, default port.
        }
        if (host.isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS,
                    "Cannot derive HTTP service from Host header: " + hostHeader);
        }
        if (port == -1) {
            port = 80;
        }
        return new ServiceParts(host, port, port == 443);
    }

    private static int parsePort(String text, String original) {
        try {
            int port = Integer.parseInt(text.trim());
            if (port < 1 || port > 65535) {
                throw new McpError(McpError.INVALID_PARAMS,
                        "Cannot derive HTTP service from Host header: " + original);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new McpError(McpError.INVALID_PARAMS,
                    "Cannot derive HTTP service from Host header: " + original);
        }
    }
}
