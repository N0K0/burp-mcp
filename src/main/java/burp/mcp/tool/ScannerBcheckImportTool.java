package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.scanner.bchecks.BCheckImportResult;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Import a custom BCheck scan definition into Burp's Scanner.
 * Accepts the definition inline ('content') or from a file ('path').
 */
public class ScannerBcheckImportTool extends ScannerBase implements Tool {

    public ScannerBcheckImportTool(MontoyaApi api) {
        super(api);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "scanner_bcheck_import",
                "Import a custom BCheck scan definition. Provide either 'content' (the .bcheck definition text) or 'path' (file to read it from). Requires Burp Suite Professional.",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("content", McpJson.property("string", "BCheck definition text"));
        props.set("path", McpJson.property("string", "File path to read the BCheck definition from"));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        checkProEdition();

        String definition = readDefinition(args);

        BCheckImportResult result;
        try {
            result = api.scanner().bChecks().importBCheck(definition);
        } catch (Exception e) {
            throw new McpError(McpError.INTERNAL_ERROR,
                    "Failed to import BCheck: " + e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        try {
            out.put("status", String.valueOf(result.status()));
        } catch (Exception e) {
            out.put("status", "unknown");
        }
        List<String> errors = new ArrayList<>();
        try {
            if (result.importErrors() != null) {
                errors.addAll(result.importErrors());
            }
        } catch (Exception ignored) {
        }
        out.put("importErrors", errors);
        return out;
    }

    /** Resolve the definition text from 'content' or 'path'. Package-visible for testing. */
    static String readDefinition(Map<String, Object> args) {
        Object content = args.get("content");
        Object path = args.get("path");
        boolean hasContent = content instanceof String && !((String) content).isEmpty();
        boolean hasPath = path instanceof String && !((String) path).isEmpty();
        if (hasContent && hasPath) {
            throw new McpError(McpError.INVALID_PARAMS,
                    "Provide either 'content' or 'path', not both");
        }
        if (hasContent) {
            return (String) content;
        }
        if (hasPath) {
            String filePath = (String) path;
            String pathError = burp.mcp.util.InputValidator.validateFilePath(filePath, "'path'");
            if (pathError != null) {
                throw new McpError(McpError.INVALID_PARAMS, pathError);
            }
            try {
                return java.nio.file.Files.readString(java.nio.file.Paths.get(filePath));
            } catch (Exception e) {
                throw new McpError(McpError.INVALID_PARAMS,
                        "Cannot read BCheck file: " + filePath);
            }
        }
        throw new McpError(McpError.INVALID_PARAMS,
                "Either 'content' or 'path' must be provided");
    }
}
