package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.proxy.ProxyWebSocketMessage;
import burp.mcp.util.ByteArrayConverter;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * List WebSocket messages from the proxy history.
 */
public class ProxyWebSocketHistoryTool implements Tool {

    private final MontoyaApi api;

    public ProxyWebSocketHistoryTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "proxy_websocket_history_list",
                "List WebSocket messages captured by the proxy. Use 'limit' to cap the number of results (default 500). Returns message URL, timestamp, direction, content, and size.",
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
        if (args.containsKey("limit") && args.get("limit") instanceof Number) {
            limit = ((Number) args.get("limit")).intValue();
            if (limit <= 0) limit = 500;
        }

        List<ProxyWebSocketMessage> entries = api.proxy().webSocketHistory();
        List<Map<String, Object>> result = new ArrayList<>();

        for (ProxyWebSocketMessage msg : entries) {
            if (result.size() >= limit) break;

            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("url", msg.upgradeRequest().url().toString());
            item.put("time", msg.time().toString());
            item.put("direction", msg.direction().name());

            byte[] payloadBytes = msg.payload().getBytes();
            item.put("message", ByteArrayConverter.bytesToString(payloadBytes));
            item.put("messageSize", payloadBytes.length);

            result.add(item);
        }

        return result;
    }
}
