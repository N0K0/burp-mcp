package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Send data items to the Comparer tool.
 */
public class HttpSendToComparerTool implements Tool {

    private final MontoyaApi api;

    public HttpSendToComparerTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "http_send_to_comparer",
                "Send data items to Burp Comparer for side-by-side comparison. Provide 'data' as an array of strings (at least 2 items).",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("data", McpJson.property("array", "The data string to decode or process"));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        required.add("data");
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        Object dataObj = args.get("data");
        if (!(dataObj instanceof List)) {
            throw new McpError(McpError.INVALID_PARAMS, "'data' must be an array of strings");
        }

        List<?> dataList = (List<?>) dataObj;
        if (dataList.size() < 2) {
            throw new McpError(McpError.INVALID_PARAMS,
                    "'data' must contain at least 2 items for comparison");
        }

        List<ByteArray> byteArrays = new ArrayList<>();
        for (Object item : dataList) {
            if (!(item instanceof String)) {
                throw new McpError(McpError.INVALID_PARAMS, "All items in 'data' must be strings");
            }
            String s = (String) item;
            byteArrays.add(ByteArray.byteArray(s.getBytes(StandardCharsets.UTF_8)));
        }

        try {
            api.comparer().sendToComparer(byteArrays.toArray(new ByteArray[0]));
        } catch (Exception e) {
            throw new McpError(McpError.INTERNAL_ERROR,
                    "Failed to send data to Comparer: " + e.getMessage());
        }

        ObjectNode result = McpJson.createObjectNode();
        result.put("success", true);
        return result;
    }
}
