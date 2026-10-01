package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Full-text regex search across sitemap request/response bodies.
 */
public class SitemapSearchTool implements Tool {

    private final MontoyaApi api;

    public SitemapSearchTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("sitemap_search",
                "Full-text regex search across sitemap request and response bodies. "
                + "Returns matching URLs with match context.",
                inputSchema());
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = McpJson.createObjectNode();
        props.set("pattern", McpJson.property("string", "Regex pattern to search for"));
        props.set("limit", McpJson.property("integer", "Maximum number of results to return", 500));
        schema.set("properties", props);
        ArrayNode required = McpJson.createArrayNode();
        required.add("pattern");
        schema.set("required", required);
        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String patternStr = args.get("pattern") instanceof String s ? s : "";
        if (patternStr.isEmpty()) throw new McpError(McpError.INVALID_PARAMS, "pattern is required");
        if (patternStr.length() > 2000) {
            throw new McpError(McpError.INVALID_PARAMS, "pattern must not exceed 2000 characters");
        }
        int limit = args.get("limit") instanceof Number n ? n.intValue() : 500;
        if (limit <= 0) {
            throw new McpError(McpError.INVALID_PARAMS, "'limit' must be a positive integer");
        }
        if (limit > 5000) limit = 5000;

        Pattern pattern;
        try {
            pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        } catch (PatternSyntaxException e) {
            throw new McpError(McpError.INVALID_PARAMS, "Invalid regex: " + e.getMessage());
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (var entry : api.siteMap().requestResponses()) {
            if (results.size() >= limit) break;

            HttpRequest req = entry.request();
            if (req == null) continue;
            String entryUrl = req.url();

            // Search request body
            if (req.body() != null) {
                String body = new String(req.body().getBytes(), StandardCharsets.UTF_8);
                var matcher = pattern.matcher(body);
                if (matcher.find()) {
                    results.add(buildHit(entryUrl, "request", body, matcher));
                }
            }

            // Search response body
            if (entry.response() != null && entry.response().body() != null) {
                String respBody = new String(entry.response().body().getBytes(), StandardCharsets.UTF_8);
                var matcher = pattern.matcher(respBody);
                if (matcher.find()) {
                    results.add(buildHit(entryUrl, "response", respBody, matcher));
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", results.size());
        out.put("truncated", results.size() >= limit);
        out.put("results", results);
        return out;
    }

    private Map<String, Object> buildHit(String url, String location, String body, java.util.regex.Matcher matcher) {
        int start = Math.max(0, matcher.start() - 40);
        int end = Math.min(body.length(), matcher.end() + 40);
        String context = body.substring(start, end);
        if (start > 0) context = "..." + context;
        if (end < body.length()) context = context + "...";

        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("url", url);
        hit.put("location", location);
        hit.put("match", matcher.group());
        hit.put("context", context);
        return hit;
    }
}
