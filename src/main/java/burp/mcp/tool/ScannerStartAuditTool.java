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
                "Start a vulnerability scan on one or more URLs. Provide 'url' (single string) or 'urls' (array of strings). Optional 'config' parameter: 'LEGACY_ACTIVE_AUDIT_CHECKS' (default) or 'LEGACY_PASSIVE_AUDIT_CHECKS'. Requires Burp Suite Professional.",
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

        // Determine config
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
            default:
                config = BuiltInAuditConfiguration.LEGACY_ACTIVE_AUDIT_CHECKS;
                break;
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
                HttpRequest request = HttpRequest.httpRequestFromUrl(url);
                audit.addRequest(request);
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
}
