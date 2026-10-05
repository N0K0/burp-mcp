package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.organizer.OrganizerItem;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * List items stashed in Burp Organizer (manual follow-up store).
 */
public class OrganizerListTool implements Tool {

    private final MontoyaApi api;

    public OrganizerListTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "organizer_list",
                "List items stored in Burp Organizer. Optional 'limit' caps the entries returned.",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("limit", McpJson.property("integer", "Max items to return (default 100, max 1000)", 100));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        int limit = 100;
        Object limitObj = args.get("limit");
        if (limitObj instanceof Number) {
            limit = Math.min(1000, Math.max(1, ((Number) limitObj).intValue()));
        }

        List<OrganizerItem> items;
        try {
            items = api.organizer().items();
        } catch (Exception e) {
            throw new McpError(McpError.INTERNAL_ERROR,
                    "Failed to list Organizer items: " + e.getMessage());
        }

        List<Map<String, Object>> out = new ArrayList<>();
        int shown = 0;
        if (items != null) {
            for (OrganizerItem item : items) {
                if (shown >= limit) {
                    break;
                }
                out.add(serialize(item));
                shown++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", items == null ? 0 : items.size());
        result.put("shown", shown);
        result.put("truncated", items != null && items.size() > shown);
        result.put("items", out);
        return result;
    }

    private static Map<String, Object> serialize(OrganizerItem item) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            m.put("id", item.id());
        } catch (Exception ignored) {
        }
        try {
            m.put("status", String.valueOf(item.status()));
        } catch (Exception ignored) {
        }
        try {
            m.put("url", item.request().url());
        } catch (Exception ignored) {
        }
        try {
            m.put("method", item.request().method());
        } catch (Exception ignored) {
        }
        try {
            m.put("statusCode", item.response() == null ? 0 : item.response().statusCode());
        } catch (Exception ignored) {
        }
        return m;
    }
}
