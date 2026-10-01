package burp.mcp.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * JSON-RPC 2.0 encoding/decoding utilities.
 */
public class McpJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.EAGER_DESERIALIZER_FETCH, true)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    // JSON-RPC request: { jsonrpc, method, params, id }
    public static class JsonRpcRequest {
        public String jsonrpc;
        public String method;
        public Map<String, Object> params;
        public Object id;
    }

    // JSON-RPC response: { jsonrpc, result, id }
    public static class JsonRpcResponse {
        public String jsonrpc = "2.0";
        public Object result;
        public Object id;

        public static JsonRpcResponse ok(Object result, Object id) {
            JsonRpcResponse r = new JsonRpcResponse();
            r.result = result;
            r.id = id;
            return r;
        }
    }

    // JSON-RPC error: { jsonrpc, error: { code, message, data }, id }
    public static class JsonRpcError {
        public String jsonrpc = "2.0";
        public ErrorObject error;
        public Object id;

        public static class ErrorObject {
            public int code;
            public String message;
            public Object data;

            public ErrorObject(int code, String message, Object data) {
                this.code = code;
                this.message = message;
                this.data = data;
            }
        }

        public static JsonRpcError error(int code, String message, Object data, Object id) {
            JsonRpcError e = new JsonRpcError();
            e.error = new ErrorObject(code, message, data);
            e.id = id;
            return e;
        }
    }

    public static JsonRpcRequest parseRequest(String json) {
        try {
            return MAPPER.readValue(json, JsonRpcRequest.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            // Never return Java toString() here — callers send this straight
            // to the client as JSON. Fall back to a minimal static payload.
            String msg = e.getMessage() == null ? "serialization failed"
                    : e.getMessage().replace("\"", "'").replace("\n", " ");
            if (msg.length() > 200) {
                msg = msg.substring(0, 200) + "...";
            }
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":"
                    + McpError.INTERNAL_ERROR
                    + ",\"message\":\"Failed to serialize response: "
                    + msg + "\"},\"id\":null}";
        }
    }

    public static String buildResponse(Object result, Object id) {
        return toJson(JsonRpcResponse.ok(result, id));
    }

    public static String buildError(int code, String message, Object data, Object id) {
        return toJson(JsonRpcError.error(code, message, data, id));
    }

    public static ObjectNode createObjectNode() {
        return JsonNodeFactory.instance.objectNode();
    }

    public static ArrayNode createArrayNode() {
        return JsonNodeFactory.instance.arrayNode();
    }

    /**
     * Create a JSON Schema property node with type and description.
     * Example: property("string", "The target URL")
     * produces: {"type": "string", "description": "The target URL"}
     */
    public static ObjectNode property(String type, String description) {
        ObjectNode node = createObjectNode();
        node.put("type", type);
        node.put("description", description);
        return node;
    }

    /**
     * Create a JSON Schema property node with type, description, and default value.
     * Example: property("boolean", "Follow redirects", true)
     * produces: {"type": "boolean", "description": "Follow redirects", "default": true}
     */
    public static ObjectNode property(String type, String description, Object defaultValue) {
        ObjectNode node = property(type, description);
        if (defaultValue instanceof String) {
            node.put("default", (String) defaultValue);
        } else if (defaultValue instanceof Boolean) {
            node.put("default", (Boolean) defaultValue);
        } else if (defaultValue instanceof Integer) {
            node.put("default", (Integer) defaultValue);
        } else if (defaultValue instanceof Long) {
            node.put("default", (Long) defaultValue);
        } else if (defaultValue instanceof Double) {
            node.put("default", (Double) defaultValue);
        }
        return node;
    }

    /**
     * Generate an example/default JSON object from a JSON Schema properties node.
     * Returns a pretty-printed JSON string with sensible defaults for each property.
     */
    public static String generateExampleJson(ObjectNode schema) {
        ObjectNode example = createObjectNode();
        if (schema == null || !schema.has("properties")) {
            return "{ }";
        }
        com.fasterxml.jackson.databind.JsonNode propsNode = schema.get("properties");
        if (!(propsNode instanceof ObjectNode props)) {
            return "{ }";
        }
        var fields = props.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String key = field.getKey();
            var prop = field.getValue();
            String type = prop.has("type") ? prop.get("type").asText() : "string";
            example.set(key, defaultValueForType(type, prop));
        }
        try {
            return mapper().writerWithDefaultPrettyPrinter().writeValueAsString(example);
        } catch (Exception e) {
            return "{ }";
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode defaultValueForType(String type, com.fasterxml.jackson.databind.JsonNode prop) {
        // Use explicit default if present
        if (prop.has("default")) {
            return prop.get("default");
        }
        switch (type) {
            case "string":  return JsonNodeFactory.instance.textNode("");
            case "integer": return JsonNodeFactory.instance.numberNode(0);
            case "number":  return JsonNodeFactory.instance.numberNode(0);
            case "boolean": return JsonNodeFactory.instance.booleanNode(false);
            case "array":   return JsonNodeFactory.instance.arrayNode();
            case "object":  return JsonNodeFactory.instance.objectNode();
            default:        return JsonNodeFactory.instance.textNode("");
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
