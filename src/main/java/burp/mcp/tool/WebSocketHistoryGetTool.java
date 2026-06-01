package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.proxy.ProxyWebSocketMessage;
import burp.mcp.util.ByteArrayConverter;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Get a specific WebSocket message by index from the proxy WebSocket history.
 * Returns gracefully when no messages are captured or index is out of range.
 */
public class WebSocketHistoryGetTool implements Tool {

    private final MontoyaApi api;

    public WebSocketHistoryGetTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("websocket_history_get",
                "Get a specific WebSocket message by its index (0-based) from the proxy WebSocket history. "
                + "Returns a 'found' field indicating success; never throws on empty history.",
                inputSchema());
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = McpJson.createObjectNode();
        props.set("index", McpJson.property("integer", "0-based index of the WebSocket message in proxy history"));
        schema.set("properties", props);
        ArrayNode required = McpJson.createArrayNode();
        required.add("index");
        schema.set("required", required);
        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        int index = args.get("index") instanceof Number n ? n.intValue() : -1;
        if (index < 0) throw new McpError(McpError.INVALID_PARAMS, "'index' must be a non-negative integer");

        List<ProxyWebSocketMessage> entries = api.proxy().webSocketHistory();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", index);
        result.put("total_messages", entries.size());
        result.put("history_empty", entries.isEmpty());

        if (entries.isEmpty()) {
            result.put("found", false);
            result.put("message", "No WebSocket messages captured in proxy history. Browse a site with WebSocket traffic first.");
            return result;
        }

        if (index >= entries.size()) {
            result.put("found", false);
            result.put("message", "Index " + index + " is out of range. Valid range: 0-" + (entries.size() - 1));
            return result;
        }

        ProxyWebSocketMessage msg = entries.get(index);
        result.put("found", true);
        result.put("url", msg.upgradeRequest().url().toString());
        result.put("time", msg.time().toString());
        result.put("direction", msg.direction().name());

        byte[] payloadBytes = msg.payload().getBytes();
        result.put("message", ByteArrayConverter.bytesToString(payloadBytes));
        result.put("messageSize", payloadBytes.length);

        return result;
    }
}
