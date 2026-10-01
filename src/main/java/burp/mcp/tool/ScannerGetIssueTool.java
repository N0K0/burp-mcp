package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.scanner.audit.issues.AuditIssue;
import burp.mcp.util.HttpMessageSerializer;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Get detailed information about a specific scanner issue.
 */
public class ScannerGetIssueTool extends ScannerBase implements Tool {

    public ScannerGetIssueTool(MontoyaApi api) {
        super(api);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "scanner_get_issue",
                "Get full details of a specific vulnerability issue. Provide 'url' (the URL where the issue was found) and optionally 'name' (the issue name for disambiguation if multiple issues match the URL).",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("url", McpJson.property("string", "Target URL (creates a GET request if used alone)"));
        props.set("name", McpJson.property("string", "Issue name to look up"));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        required.add("url");
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        checkProEdition();
        String url = (String) args.get("url");
        String name = (String) args.get("name");

        if (url == null || url.isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS, "'url' is required");
        }

        List<AuditIssue> issues;
        try {
            issues = api.siteMap().issues();
        } catch (Exception e) {
            throw new McpError(McpError.INTERNAL_ERROR,
                    "Failed to retrieve issues: " + e.getMessage());
        }

        // Find matching issue
        AuditIssue matched = null;
        for (AuditIssue issue : issues) {
            if (!issue.baseUrl().startsWith(url)) {
                continue;
            }
            // If name is specified, it must also match
            if (name != null && !name.isEmpty()) {
                if (!issue.name().equals(name)) {
                    continue;
                }
            }
            matched = issue;
            break;
        }

        if (matched == null) {
            throw new McpError(McpError.NOT_FOUND,
                    "No issue found matching url: " + url +
                    (name != null ? ", name: " + name : ""));
        }

        return HttpMessageSerializer.serializeAuditIssue(matched);
    }
}
