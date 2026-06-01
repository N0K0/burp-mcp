package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.mcp.util.ByteArrayConverter;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Compare two HTTP responses to identify variant and invariant attributes.
 * Does a manual comparison of status code, headers, body size, MIME type, etc.
 */
public class HttpDiffResponsesTool implements Tool {

    private final MontoyaApi api;

    public HttpDiffResponsesTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "http_diff_responses",
                "Compare two HTTP responses and identify the attributes that differ (variant) and those that remain the same (invariant). Provide 'response1' and 'response2' as raw HTTP response strings.",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("response1", McpJson.property("string", "First raw HTTP response string to compare"));
        props.set("response2", McpJson.property("string", "Second raw HTTP response string to compare"));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        required.add("response1");
        required.add("response2");
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String response1Str = (String) args.get("response1");
        String response2Str = (String) args.get("response2");

        if (response1Str == null || response1Str.isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS, "'response1' is required");
        }
        if (response2Str == null || response2Str.isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS, "'response2' is required");
        }

        HttpResponse response1;
        HttpResponse response2;
        try {
            response1 = HttpResponse.httpResponse(response1Str);
        } catch (Exception e) {
            throw new McpError(McpError.INVALID_PARAMS,
                    "Failed to parse response1: " + e.getMessage());
        }
        try {
            response2 = HttpResponse.httpResponse(response2Str);
        } catch (Exception e) {
            throw new McpError(McpError.INVALID_PARAMS,
                    "Failed to parse response2: " + e.getMessage());
        }

        ObjectNode result = McpJson.createObjectNode();

        // Collect comparable attributes
        Map<String, Object> attrs1 = new LinkedHashMap<>();
        Map<String, Object> attrs2 = new LinkedHashMap<>();

        // Status code
        attrs1.put("status_code", response1.statusCode());
        attrs2.put("status_code", response2.statusCode());

        // Reason phrase
        attrs1.put("reason_phrase", response1.reasonPhrase());
        attrs2.put("reason_phrase", response2.reasonPhrase());

        // MIME type
        attrs1.put("mime_type", response1.mimeType().name());
        attrs2.put("mime_type", response2.mimeType().name());

        // Body size
        attrs1.put("body_size", (long) response1.body().length());
        attrs2.put("body_size", (long) response2.body().length());

        // Body content (truncated for comparison)
        String body1 = ByteArrayConverter.bytesToString(response1.body().getBytes());
        String body2 = ByteArrayConverter.bytesToString(response2.body().getBytes());
        attrs1.put("body_content", body1);
        attrs2.put("body_content", body2);

        // Collect all header names from both responses
        java.util.Set<String> allHeaderNames = new java.util.HashSet<>();
        for (HttpHeader h : response1.headers()) allHeaderNames.add(h.name().toLowerCase());
        for (HttpHeader h : response2.headers()) allHeaderNames.add(h.name().toLowerCase());

        // Build header value maps for each response
        Map<String, String> headers1 = new LinkedHashMap<>();
        for (HttpHeader h : response1.headers()) {
            String key = h.name().toLowerCase();
            headers1.put(key, headers1.containsKey(key) ? headers1.get(key) + ", " + h.value() : h.value());
        }
        Map<String, String> headers2 = new LinkedHashMap<>();
        for (HttpHeader h : response2.headers()) {
            String key = h.name().toLowerCase();
            headers2.put(key, headers2.containsKey(key) ? headers2.get(key) + ", " + h.value() : h.value());
        }

        // Add each header as a comparable attribute
        for (String headerName : allHeaderNames) {
            String attrName = "header_" + headerName;
            String val1 = headers1.get(headerName);
            String val2 = headers2.get(headerName);
            attrs1.put(attrName, val1 != null ? val1 : "(absent)");
            attrs2.put(attrName, val2 != null ? val2 : "(absent)");
        }

        // Compare and classify as variant or invariant
        ArrayNode variantArray = (ArrayNode) com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        ArrayNode invariantArray = (ArrayNode) com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();

        for (Map.Entry<String, Object> entry : attrs1.entrySet()) {
            String attrName = entry.getKey();
            Object val1 = entry.getValue();
            Object val2 = attrs2.get(attrName);

            ObjectNode attrNode = McpJson.createObjectNode();
            attrNode.put("attribute", attrName);

            // Truncate large body values for display
            String v1Str = String.valueOf(val1);
            String v2Str = String.valueOf(val2);
            if (v1Str.length() > 200) v1Str = v1Str.substring(0, 200) + "... (truncated)";
            if (v2Str.length() > 200) v2Str = v2Str.substring(0, 200) + "... (truncated)";

            attrNode.put("value1", v1Str);
            attrNode.put("value2", v2Str);

            if (val1 != null && val2 != null && val1.equals(val2)) {
                invariantArray.add(attrNode);
            } else {
                variantArray.add(attrNode);
            }
        }

        result.set("variant_attributes", variantArray);
        result.set("invariant_attributes", invariantArray);

        return result;
    }
}
