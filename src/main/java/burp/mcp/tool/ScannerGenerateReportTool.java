package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.scanner.ReportFormat;
import burp.api.montoya.scanner.audit.issues.AuditIssue;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generate a scanner report.
 */
public class ScannerGenerateReportTool extends ScannerBase implements Tool {

    public ScannerGenerateReportTool(MontoyaApi api) {
        super(api);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "scanner_generate_report",
                "Generate a vulnerability report. Optional 'format' (html or xml, default html) and 'issue_urls' (array of URL strings to include; if omitted, all issues are included). Returns the file path and size.",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("format", McpJson.property("string", "Report format: 'html' (default) or 'xml'", "html"));
        props.set("issue_urls", McpJson.property("array", "Array of specific issue URLs to include (omit for all issues)"));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        checkProEdition();

        String formatStr = (String) args.get("format");
        if (formatStr == null || formatStr.isEmpty()) {
            formatStr = "html";
        }

        ReportFormat format;
        switch (formatStr.toLowerCase()) {
            case "xml":
                format = ReportFormat.XML;
                break;
            case "html":
            default:
                format = ReportFormat.HTML;
                break;
        }

        // Determine which issues to include
        List<AuditIssue> issuesToReport = new ArrayList<>();
        Object urlsObj = args.get("issue_urls");

        if (urlsObj instanceof List && !((List<?>) urlsObj).isEmpty()) {
            // Filter issues by URLs
            List<AuditIssue> allIssues = api.siteMap().issues();
            for (AuditIssue issue : allIssues) {
                for (Object urlObj : (List<?>) urlsObj) {
                    if (urlObj instanceof String && issue.baseUrl().startsWith((String) urlObj)) {
                        issuesToReport.add(issue);
                        break;
                    }
                }
            }
        } else {
            // Include all issues
            issuesToReport = api.siteMap().issues();
        }

        if (issuesToReport.isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS,
                    "No issues found to include in report");
        }

        // Generate report file path
        String reportFileName = "burp_report_" + System.currentTimeMillis() + "." +
                (format == ReportFormat.HTML ? "html" : "xml");
        Path reportPath = Paths.get(System.getProperty("java.io.tmpdir"), reportFileName);

        try {
            api.scanner().generateReport(issuesToReport, format, reportPath);
        } catch (Exception e) {
            throw new McpError(McpError.INTERNAL_ERROR,
                    "Failed to generate report: " + e.getMessage());
        }

        File reportFile = reportPath.toFile();
        long sizeBytes = reportFile.length();

        ObjectNode result = McpJson.createObjectNode();
        result.put("report_path", reportPath.toString());
        result.put("size_bytes", sizeBytes);
        result.put("format", formatStr.toUpperCase());
        return result;
    }
}
