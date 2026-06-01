package burp.mcp.util;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpJsonTest {

    @Test
    void parseRequest_shouldParseValidJsonRpc() {
        String json = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":\"1\"}";
        McpJson.JsonRpcRequest req = McpJson.parseRequest(json);
        assertThat(req).isNotNull();
        assertThat(req.jsonrpc).isEqualTo("2.0");
        assertThat(req.method).isEqualTo("tools/list");
        assertThat(req.id).isEqualTo("1");
    }

    @Test
    void parseRequest_shouldReturnNullForInvalidJson() {
        McpJson.JsonRpcRequest req = McpJson.parseRequest("not json");
        assertThat(req).isNull();
    }

    @Test
    void parseRequest_shouldParseToolsCallWithParams() {
        String json = "{" +
            "\"jsonrpc\":\"2.0\"," +
            "\"method\":\"tools/call\"," +
            "\"params\":{" +
                "\"name\":\"burp_info\"," +
                "\"arguments\":{}" +
            "}," +
            "\"id\":3" +
        "}";
        McpJson.JsonRpcRequest req = McpJson.parseRequest(json);
        assertThat(req).isNotNull();
        assertThat(req.method).isEqualTo("tools/call");
        assertThat(req.params).isNotNull();
        assertThat(req.params.get("name")).isEqualTo("burp_info");
        assertThat(req.id).isEqualTo(3);
    }

    @Test
    void buildResponse_shouldProduceValidJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        String json = McpJson.buildResponse(result, "1");
        assertThat(json).contains("\"jsonrpc\":\"2.0\"");
        assertThat(json).contains("\"status\":\"ok\"");
        assertThat(json).contains("\"id\":\"1\"");
    }

    @Test
    void buildError_shouldProduceErrorJson() {
        String json = McpJson.buildError(-32600, "Invalid Request", null, "1");
        assertThat(json).contains("\"code\":-32600");
        assertThat(json).contains("\"message\":\"Invalid Request\"");
    }

    @Test
    void toJson_shouldSerializeObject() {
        Map<String, String> obj = new LinkedHashMap<>();
        obj.put("key", "value");
        String json = McpJson.toJson(obj);
        assertThat(json).isEqualTo("{\"key\":\"value\"}");
    }

    @Test
    void createObjectNode_shouldReturnEmptyNode() {
        ObjectNode node = McpJson.createObjectNode();
        assertThat(node).isNotNull();
        assertThat(node.isEmpty()).isTrue();
    }

    @Test
    void createArrayNode_shouldReturnEmptyArray() {
        ArrayNode node = McpJson.createArrayNode();
        assertThat(node).isNotNull();
        assertThat(node.isEmpty()).isTrue();
    }

    @Test
    void property_shouldCreateTypedNode() {
        ObjectNode prop = McpJson.property("string", "A test property");
        assertThat(prop.get("type").asText()).isEqualTo("string");
        assertThat(prop.get("description").asText()).isEqualTo("A test property");
    }

    @Test
    void property_withDefault_shouldIncludeDefault() {
        ObjectNode prop = McpJson.property("boolean", "Follow redirects", true);
        assertThat(prop.get("type").asText()).isEqualTo("boolean");
        assertThat(prop.get("default").asBoolean()).isTrue();

        ObjectNode prop2 = McpJson.property("integer", "Limit", 500);
        assertThat(prop2.get("default").asInt()).isEqualTo(500);

        ObjectNode prop3 = McpJson.property("string", "Method", "GET");
        assertThat(prop3.get("default").asText()).isEqualTo("GET");
    }

    @Test
    void generateExampleJson_shouldCreateDefaultsFromSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = McpJson.createObjectNode();
        props.set("url", McpJson.property("string", "Target URL"));
        props.set("follow_redirects", McpJson.property("boolean", "Follow", true));
        props.set("limit", McpJson.property("integer", "Limit", 500));
        schema.set("properties", props);

        String example = McpJson.generateExampleJson(schema);
        assertThat(example).contains("\"url\" : \"\"");
        assertThat(example).contains("\"follow_redirects\" : true");
        assertThat(example).contains("\"limit\" : 500");
    }

    @Test
    void generateExampleJson_emptySchema_shouldReturnEmpty() {
        String example = McpJson.generateExampleJson(null);
        assertThat(example).isEqualTo("{ }");

        ObjectNode empty = McpJson.createObjectNode();
        String example2 = McpJson.generateExampleJson(empty);
        assertThat(example2).isEqualTo("{ }");
    }
}
