package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Set a cookie in the Burp cookie jar.
 */
public class CookieSetTool implements Tool {

    private final MontoyaApi api;

    public CookieSetTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "cookie_set",
                "Set a cookie in Burp's cookie jar. Required: name, value, domain. Optional: path (default '/'), expiration (ISO 8601 date string).",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("name", McpJson.property("string", "Cookie name"));
        props.set("value", McpJson.property("string", "Cookie value"));
        props.set("domain", McpJson.property("string", "Cookie domain (e.g., example.com)"));
        props.set("path", McpJson.property("string", "Cookie path (default: '/')", "/"));
        props.set("expiration", McpJson.property("string", "Expiration date in ISO 8601 format (e.g., 2026-12-31T23:59:59Z)", ""));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        required.add("name");
        required.add("value");
        required.add("domain");
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String name = (String) args.get("name");
        String value = (String) args.get("value");
        String domain = (String) args.get("domain");

        if (name == null || name.isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS, "'name' is required");
        }
        String nameError = burp.mcp.util.InputValidator.validateCookieValue(name, "'name'");
        if (nameError != null) {
            throw new McpError(McpError.INVALID_PARAMS, nameError);
        }
        if (value == null) {
            throw new McpError(McpError.INVALID_PARAMS, "'value' is required");
        }
        String valueError = burp.mcp.util.InputValidator.validateCookieValue(value, "'value'");
        if (valueError != null) {
            throw new McpError(McpError.INVALID_PARAMS, valueError);
        }
        if (domain == null || domain.isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS, "'domain' is required");
        }

        String path = (String) args.get("path");
        if (path == null || path.isEmpty()) {
            path = "/";
        }

        ZonedDateTime expiration = null;
        String expirationStr = (String) args.get("expiration");
        if (expirationStr != null && !expirationStr.isEmpty()) {
            try {
                expiration = ZonedDateTime.parse(expirationStr, DateTimeFormatter.ISO_ZONED_DATE_TIME);
            } catch (DateTimeParseException e) {
                try {
                    expiration = ZonedDateTime.parse(expirationStr);
                } catch (DateTimeParseException e2) {
                    throw new McpError(McpError.INVALID_PARAMS,
                            "'expiration' must be a valid ISO 8601 date string: " + e2.getMessage());
                }
            }
        }

        try {
            api.http().cookieJar().setCookie(name, value, path, domain, expiration);
        } catch (Exception e) {
            throw new McpError(McpError.INTERNAL_ERROR,
                    "Failed to set cookie: " + e.getMessage());
        }

        ObjectNode result = McpJson.createObjectNode();
        result.put("success", true);
        return result;
    }
}
