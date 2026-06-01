package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists all in-scope URLs by scanning the sitemap and checking scope membership.
 */
public class ScopeListTool implements Tool {

    private final MontoyaApi api;

    public ScopeListTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("scope_list",
                "List all URLs currently in the target scope (derived from sitemap entries).",
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
        List<String> urls = new ArrayList<>();
        for (var entry : api.siteMap().requestResponses()) {
            String url = entry.url();
            if (url != null && api.scope().isInScope(url)) {
                urls.add(url);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", urls.size());
        out.put("urls", urls);
        return out;
    }
}
