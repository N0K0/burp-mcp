package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.scanner.audit.issues.AuditIssue;
import burp.mcp.util.HttpMessageSerializer;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * List all scanner issues from the site map.
 */
public class ScannerIssuesListTool extends ScannerBase implements Tool {

    public ScannerIssuesListTool(MontoyaApi api) {
        super(api);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "scanner_issues_list",
                "List all vulnerability issues found in the site map. Optional 'limit' parameter to cap the number of results (default 500).",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("limit", McpJson.property("integer", "Maximum number of entries to return (default: 500)", 500));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        int limit = 500;
        Object limitObj = args.get("limit");
        if (limitObj instanceof Number) {
            limit = ((Number) limitObj).intValue();
            if (limit <= 0) {
                throw new McpError(McpError.INVALID_PARAMS, "'limit' must be a positive integer");
            }
        }

        List<AuditIssue> issues;
        try {
            issues = api.siteMap().issues();
        } catch (Exception e) {
            throw new McpError(McpError.INTERNAL_ERROR,
                    "Failed to retrieve issues: " + e.getMessage());
        }

        int count = Math.min(limit, issues.size());
        ArrayNode result = (ArrayNode) com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();

        for (int i = 0; i < count; i++) {
            AuditIssue issue = issues.get(i);
            Map<String, Object> issueMap = HttpMessageSerializer.summarizeIssue(issue);
            result.add(McpJson.mapper().valueToTree(issueMap));
        }

        return result;
    }
}
