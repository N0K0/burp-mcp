package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.Cookie;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * List all cookies in the Burp cookie jar.
 */
public class CookieListTool implements Tool {

    private final MontoyaApi api;

    public CookieListTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "cookie_list",
                "List all cookies stored in Burp's cookie jar. Returns an array of cookie objects with name, value, domain, path, and expiration fields.",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        List<Cookie> cookies;
        try {
            cookies = api.http().cookieJar().cookies();
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve cookies: " + e.getMessage(), e);
        }

        ArrayNode result = (ArrayNode) com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();

        for (Cookie cookie : cookies) {
            ObjectNode cookieNode = McpJson.createObjectNode();
            cookieNode.put("name", cookie.name());
            cookieNode.put("value", cookie.value());
            cookieNode.put("domain", cookie.domain());
            cookieNode.put("path", cookie.path());
            cookieNode.put("expiration",
                    cookie.expiration().map(Object::toString).orElse(null));
            result.add(cookieNode);
        }

        return result;
    }
}
