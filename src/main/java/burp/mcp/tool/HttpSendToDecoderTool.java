package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Send data to the Decoder tool.
 */
public class HttpSendToDecoderTool implements Tool {

    private final MontoyaApi api;

    public HttpSendToDecoderTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "http_send_to_decoder",
                "Send data to Burp Decoder for decoding and encoding operations. Provide 'data' as a string to send to the Decoder tab.",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("data", McpJson.property("string", "The data string to decode or process"));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        required.add("data");
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String data = (String) args.get("data");
        if (data == null || data.isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS, "'data' is required");
        }

        try {
            api.decoder().sendToDecoder(ByteArray.byteArray(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new McpError(McpError.INTERNAL_ERROR,
                    "Failed to send data to Decoder: " + e.getMessage());
        }

        ObjectNode result = McpJson.createObjectNode();
        result.put("success", true);
        return result;
    }
}
