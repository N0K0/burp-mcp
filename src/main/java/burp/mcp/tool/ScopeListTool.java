package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists the target scope from two angles:
 * <ul>
 *   <li><b>configured_scope</b> — the actual include rules from Burp's project
 *   configuration, so includes show up even with an empty sitemap.</li>
 *   <li><b>urls/count</b> — in-scope URLs observed in sitemap traffic.</li>
 * </ul>
 */
public class ScopeListTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MontoyaApi api;

    public ScopeListTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("scope_list",
                "List the target scope: configured include rules from Burp's project configuration, plus in-scope URLs observed in sitemap traffic.",
                inputSchema());
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", McpJson.createObjectNode());
        schema.set("required", McpJson.createArrayNode());
        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        Map<String, Object> out = new LinkedHashMap<>();

        // ── Configured scope (independent of sitemap traffic) ──
        try {
            out.put("configured_scope", readConfiguredScope());
        } catch (Exception e) {
            out.put("configured_scope_unavailable", String.valueOf(e.getMessage()));
        }

        // ── Observed in-scope sitemap entries ──
        List<String> urls = new ArrayList<>();
        try {
            var entries = api.siteMap().requestResponses();
            if (entries != null) {
                for (var entry : entries) {
                    String url = entry.url();
                    if (url != null && api.scope().isInScope(url)) {
                        urls.add(url);
                    }
                }
            }
        } catch (Exception e) {
            out.put("sitemap_unavailable", String.valueOf(e.getMessage()));
        }
        out.put("count", urls.size());
        out.put("urls", urls);
        return out;
    }

    /**
     * Read the configured target scope from Burp's project options
     * ({@code target.scope}: advanced_mode + include/exclude rules).
     * Package-visible for testing.
     */
    Map<String, Object> readConfiguredScope() throws Exception {
        String json = api.burpSuite().exportProjectOptionsAsJson();
        JsonNode scope = MAPPER.readTree(json).path("target").path("scope");

        Map<String, Object> result = new LinkedHashMap<>();
        boolean advanced = scope.path("advanced_mode").asBoolean(false);
        result.put("advanced_mode", advanced);

        List<String> includes = new ArrayList<>();
        int enabledCount = 0;
        JsonNode includeRules = scope.path("include");
        if (includeRules.isArray()) {
            for (JsonNode rule : includeRules) {
                if (!rule.path("enabled").asBoolean(false)) {
                    continue;
                }
                enabledCount++;
                if (rule.has("prefix")) {
                    includes.add(rule.path("prefix").asText());
                } else {
                    // Advanced-mode rule without a plain prefix — summarize.
                    includes.add("advanced:" + compactRule(rule));
                }
            }
        }
        result.put("include_count", enabledCount);
        result.put("includes", includes);

        List<String> excludes = new ArrayList<>();
        JsonNode excludeRules = scope.path("exclude");
        if (excludeRules.isArray()) {
            for (JsonNode rule : excludeRules) {
                if (!rule.path("enabled").asBoolean(false)) {
                    continue;
                }
                if (rule.has("prefix")) {
                    excludes.add(rule.path("prefix").asText());
                } else {
                    excludes.add("advanced:" + compactRule(rule));
                }
            }
        }
        result.put("excludes", excludes);
        return result;
    }

    private static String compactRule(JsonNode rule) {
        StringBuilder sb = new StringBuilder();
        Iterator<Map.Entry<String, JsonNode>> fields = rule.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> f = fields.next();
            if ("enabled".equals(f.getKey())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(f.getKey()).append("=").append(f.getValue().asText());
        }
        return sb.toString();
    }
}
