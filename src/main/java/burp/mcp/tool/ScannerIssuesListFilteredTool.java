package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.scanner.audit.issues.AuditIssue;
import burp.api.montoya.scanner.audit.issues.AuditIssueSeverity;
import burp.mcp.util.HttpMessageSerializer;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * List scanner issues with optional filtering.
 */
public class ScannerIssuesListFilteredTool extends ScannerBase implements Tool {

    public ScannerIssuesListFilteredTool(MontoyaApi api) {
        super(api);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "scanner_issues_list_filtered",
                "List vulnerability issues with optional filters. Provide 'url_prefix' to filter by URL, 'severity' (INFORMATION, LOW, MEDIUM, HIGH, CRITICAL) to filter by severity, and 'limit' to cap results (default 500).",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("url_prefix", McpJson.property("string", "Filter results to URLs starting with this prefix"));
        props.set("severity", McpJson.property("string", "Filter by severity: INFORMATION, LOW, MEDIUM, HIGH, or CRITICAL"));
        props.set("limit", McpJson.property("integer", "Maximum number of entries to return (default: 500)", 500));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String urlPrefix = (String) args.get("url_prefix");
        String severityStr = (String) args.get("severity");
        int limit = 500;

        Object limitObj = args.get("limit");
        if (limitObj instanceof Number) {
            limit = ((Number) limitObj).intValue();
            if (limit <= 0) {
                throw new McpError(McpError.INVALID_PARAMS, "'limit' must be a positive integer");
            }
        }

        // Parse severity if provided
        AuditIssueSeverity severity = null;
        if (severityStr != null && !severityStr.isEmpty()) {
            try {
                severity = AuditIssueSeverity.valueOf(severityStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new McpError(McpError.INVALID_PARAMS,
                        "Invalid severity: " + severityStr + ". Must be one of: INFORMATION, LOW, MEDIUM, HIGH, CRITICAL");
            }
        }

        List<AuditIssue> allIssues;
        try {
            allIssues = api.siteMap().issues();
        } catch (Exception e) {
            throw new McpError(McpError.INTERNAL_ERROR,
                    "Failed to retrieve issues: " + e.getMessage());
        }

        // Apply filters
        List<AuditIssue> filtered = new ArrayList<>();
        for (AuditIssue issue : allIssues) {
            // Filter by URL prefix
            if (urlPrefix != null && !urlPrefix.isEmpty()) {
                if (!issue.baseUrl().startsWith(urlPrefix)) {
                    continue;
                }
            }

            // Filter by severity
            if (severity != null) {
                if (issue.severity() != severity) {
                    continue;
                }
            }

            filtered.add(issue);
            if (filtered.size() >= limit) break;
        }

        // Serialize results
        ArrayNode result = (ArrayNode) com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        for (AuditIssue issue : filtered) {
            Map<String, Object> issueMap = HttpMessageSerializer.summarizeIssue(issue);
            result.add(McpJson.mapper().valueToTree(issueMap));
        }

        return result;
    }
}
