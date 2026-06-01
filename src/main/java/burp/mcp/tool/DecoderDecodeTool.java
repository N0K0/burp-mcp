package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.mcp.util.ByteArrayConverter;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Decode data from base64, url, or hex encoding.
 */
public class DecoderDecodeTool implements Tool {

    private final MontoyaApi api;

    public DecoderDecodeTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "decoder_decode",
                "Decode data from base64, url, or hex encoding. Provide 'data' (the encoded string) and 'encoding' (one of: base64, url, hex).",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("data", McpJson.property("string", "The data string to decode or process"));
        props.set("encoding", McpJson.property("string", "Encoding type: one of 'base64', 'url', or 'hex'"));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        required.add("data");
        required.add("encoding");
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String data = (String) args.get("data");
        String encoding = (String) args.get("encoding");

        if (data == null || data.isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS, "'data' is required");
        }
        if (encoding == null || encoding.isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS, "'encoding' is required (base64, url, or hex)");
        }

        String decoded;
        switch (encoding.toLowerCase()) {
            case "base64":
                try {
                    byte[] decodedBytes = Base64.getDecoder().decode(data);
                    decoded = ByteArrayConverter.bytesToString(decodedBytes);
                } catch (Exception e) {
                    throw new McpError(McpError.INVALID_PARAMS,
                            "Failed to decode base64 data: " + e.getMessage());
                }
                break;

            case "url":
                try {
                    decoded = URLDecoder.decode(data, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new McpError(McpError.INVALID_PARAMS,
                            "Failed to decode URL-encoded data: " + e.getMessage());
                }
                break;

            case "hex":
                try {
                    byte[] hexBytes = ByteArrayConverter.fromHex(data);
                    decoded = ByteArrayConverter.bytesToString(hexBytes);
                } catch (Exception e) {
                    throw new McpError(McpError.INVALID_PARAMS,
                            "Failed to decode hex data: " + e.getMessage());
                }
                break;

            default:
                throw new McpError(McpError.INVALID_PARAMS,
                        "Unsupported encoding: " + encoding + ". Must be one of: base64, url, hex");
        }

        ObjectNode result = McpJson.createObjectNode();
        result.put("decoded", decoded);
        return result;
    }
}
