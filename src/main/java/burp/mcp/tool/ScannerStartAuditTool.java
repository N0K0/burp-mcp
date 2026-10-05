package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.scanner.audit.Audit;
import burp.api.montoya.scanner.AuditConfiguration;
import burp.api.montoya.scanner.BuiltInAuditConfiguration;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Start an active vulnerability scan on specified URLs.
 */
public class ScannerStartAuditTool extends ScannerBase implements Tool {

    public ScannerStartAuditTool(MontoyaApi api) {
        super(api);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "scanner_start_audit",
                "Start a vulnerability scan on one or more URLs. Provide 'url' (single string) or 'urls' (array of strings). Optional 'config' parameter: 'LEGACY_ACTIVE_AUDIT_CHECKS' (default) or 'LEGACY_PASSIVE_AUDIT_CHECKS'. Optional 'headers' object (name-value pairs, e.g. cookies/auth tokens) applied to the seed requests. NOTE: Burp's scanner stamps its own stock User-Agent on the traffic it generates — a seed User-Agent survives only as the base value for UA-insertion-point checks, not as the outgoing UA. Requires Burp Suite Professional.",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("url", McpJson.property("string", "Target URL (creates a GET request if used alone)"));
        props.set("urls", McpJson.property("array", "Array of URL strings to scan"));
        props.set("config", McpJson.property("string", "Audit configuration: 'LEGACY_ACTIVE_AUDIT_CHECKS' (default) or 'LEGACY_PASSIVE_AUDIT_CHECKS'", "LEGACY_ACTIVE_AUDIT_CHECKS"));
        props.set("headers", McpJson.property("object", "Optional HTTP headers applied to the seed requests (e.g. cookies, auth tokens). Scanner-managed headers such as User-Agent are stamped over by Burp on generated traffic"));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        checkProEdition();

        List<String> urls = new ArrayList<>();

        String singleUrl = (String) args.get("url");
        if (singleUrl != null && !singleUrl.isEmpty()) {
            urls.add(singleUrl);
        }

        Object urlsObj = args.get("urls");
        if (urlsObj instanceof List) {
            for (Object u : (List<?>) urlsObj) {
                if (u instanceof String && !((String) u).isEmpty()) {
                    urls.add((String) u);
                }
            }
        }

        if (urls.isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS,
                    "At least one URL must be provided via 'url' or 'urls'");
        }

        for (String u : urls) {
            String urlError = burp.mcp.util.InputValidator.validateUrl(u, "'url'");
            if (urlError != null) {
                throw new McpError(McpError.INVALID_PARAMS, urlError);
            }
        }

        // Determine config (unknown names are rejected, never silently escalated)
        String configName = (String) args.get("config");
        if (configName == null || configName.isEmpty()) {
            configName = "LEGACY_ACTIVE_AUDIT_CHECKS";
        }

        BuiltInAuditConfiguration config;
        switch (configName) {
            case "LEGACY_PASSIVE_AUDIT_CHECKS":
                config = BuiltInAuditConfiguration.LEGACY_PASSIVE_AUDIT_CHECKS;
                break;
            case "LEGACY_ACTIVE_AUDIT_CHECKS":
                config = BuiltInAuditConfiguration.LEGACY_ACTIVE_AUDIT_CHECKS;
                break;
            default:
                throw new McpError(McpError.INVALID_PARAMS,
                        "'config' must be one of: LEGACY_ACTIVE_AUDIT_CHECKS, LEGACY_PASSIVE_AUDIT_CHECKS");
        }

        // Start audit
        Audit audit;
        try {
            AuditConfiguration auditConfig = AuditConfiguration.auditConfiguration(config);
            audit = api.scanner().startAudit(auditConfig);
        } catch (Exception e) {
            throw new McpError(McpError.INTERNAL_ERROR,
                    "Failed to start audit: " + e.getMessage());
        }

        // Add URLs to audit
        for (String url : urls) {
            try {
                HttpRequest request = buildSeedRequest(url, args);
                audit.addRequest(request);
            } catch (McpError e) {
                throw e;
            } catch (Exception e) {
                throw new McpError(McpError.INVALID_PARAMS,
                        "Failed to add URL to audit: " + url + " - " + e.getMessage());
            }
        }

        ObjectNode result = McpJson.createObjectNode();
        result.put("statusMessage", audit.statusMessage());
        result.put("insertionPointCount", audit.insertionPointCount());
        result.put("requestCount", audit.requestCount());
        return result;
    }

    /**
     * Build an audit seed request from a URL, applying optional custom
     * headers from the 'headers' argument. Package-visible for testing.
     */
    static HttpRequest buildSeedRequest(String url, Map<String, Object> args) {
        HttpRequest request = HttpRequest.httpRequestFromUrl(url);
        Object headersObj = args.get("headers");
        if (headersObj instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) headersObj).entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new McpError(McpError.INVALID_PARAMS,
                            "All keys in 'headers' must be strings");
                }
                String name = ((String) entry.getKey()).trim();
                if (name.isEmpty()) {
                    throw new McpError(McpError.INVALID_PARAMS,
                            "Header names in 'headers' must not be empty");
                }
                request = request.withAddedHeader(name, String.valueOf(entry.getValue()));
            }
        } else if (headersObj != null) {
            throw new McpError(McpError.INVALID_PARAMS, "'headers' must be an object");
        }
        return request;
    }
}
