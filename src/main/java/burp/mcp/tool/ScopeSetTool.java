package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Add or remove a URL from Burp's target scope.
 */
public class ScopeSetTool implements Tool {

    private final MontoyaApi api;

    public ScopeSetTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "scope_set",
                "Add or remove URLs from Burp's target scope. Use 'action' set to 'include' or 'exclude'. Provide a single 'url' or bulk 'urls' array (or both). Verifies each change and returns per-URL results.",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("url", McpJson.property("string", "Single target URL to add/remove"));
        props.set("urls", McpJson.property("array", "Array of target URL strings to add/remove in one call"));
        props.set("action", McpJson.property("string", "Scope action: 'include' to add to scope, 'exclude' to remove from scope"));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        required.add("action");
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String action = (String) args.get("action");
        if (action == null || action.isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS, "'action' is required");
        }
        boolean include;
        if ("include".equalsIgnoreCase(action)) {
            include = true;
        } else if ("exclude".equalsIgnoreCase(action)) {
            include = false;
        } else {
            throw new McpError(McpError.INVALID_PARAMS,
                    "'action' must be 'include' or 'exclude', got: " + action);
        }

        // Collect single 'url' + bulk 'urls' (backwards compatible)
        java.util.List<String> urls = new java.util.ArrayList<>();
        String singleUrl = (String) args.get("url");
        if (singleUrl != null && !singleUrl.isEmpty()) {
            urls.add(singleUrl);
        }
        Object urlsObj = args.get("urls");
        if (urlsObj instanceof java.util.List) {
            for (Object u : (java.util.List<?>) urlsObj) {
                if (u instanceof String && !((String) u).isEmpty()) {
                    urls.add((String) u);
                } else {
                    throw new McpError(McpError.INVALID_PARAMS,
                            "All items in 'urls' must be non-empty strings");
                }
            }
        }
        if (urls.isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS,
                    "At least one URL must be provided via 'url' or 'urls'");
        }

        // scope methods take String (URL), not HttpRequest
        java.util.List<java.util.Map<String, Object>> results = new java.util.ArrayList<>();
        boolean allVerified = true;
        for (String url : urls) {
            String urlError = burp.mcp.util.InputValidator.validateUrl(url, "'url'");
            if (urlError != null) {
                throw new McpError(McpError.INVALID_PARAMS, urlError);
            }
            boolean verified;
            if (include) {
                api.scope().includeInScope(url);
                verified = api.scope().isInScope(url);
            } else {
                api.scope().excludeFromScope(url);
                verified = !api.scope().isInScope(url);
            }
            allVerified &= verified;
            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("url", url);
            entry.put("action", include ? "include" : "exclude");
            entry.put("success", verified);
            results.add(entry);
        }

        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("success", allVerified);
        out.put("action", include ? "include" : "exclude");
        out.put("count", results.size());
        out.put("results", results);
        if (results.size() == 1) {
            out.put("url", urls.get(0));
        }
        return out;
    }
}
