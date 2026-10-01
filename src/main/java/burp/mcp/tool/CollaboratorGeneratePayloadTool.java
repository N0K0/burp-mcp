package burp.mcp.tool;

import burp.api.montoya.core.BurpSuiteEdition;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.collaborator.CollaboratorClient;
import burp.api.montoya.collaborator.CollaboratorPayload;
import burp.api.montoya.collaborator.PayloadOption;
import burp.mcp.util.McpConfig;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Generate a Collaborator payload for DNS/log4j/etc. interaction testing.
 */
public class CollaboratorGeneratePayloadTool extends ScannerBase implements Tool {

    public CollaboratorGeneratePayloadTool(MontoyaApi api) {
        super(api);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "collaborator_generate_payload",
                "Generate a unique Collaborator Server payload for out-of-band interaction testing. Optional 'custom_data' (max 16 alphanumeric chars, used to identify interactions) and 'include_server_location' (boolean, default true). Requires Burp Suite Professional.",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("custom_data", McpJson.property("string", "Optional custom identifier (max 16 alphanumeric chars) to tag interactions"));
        props.set("include_server_location", McpJson.property("boolean", "Whether to include the server location in the payload (default: true)", true));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        checkProEdition();

        String customData = (String) args.get("custom_data");

        // Validate custom_data if provided (single source of truth)
        String customError = burp.mcp.util.InputValidator.validateCustomData(customData, "'custom_data'");
        if (customError != null) {
            throw new McpError(McpError.INVALID_PARAMS, customError);
        }

        // Create collaborator client
        CollaboratorClient client;
        try {
            client = api.collaborator().createClient();
        } catch (Exception e) {
            throw new McpError(McpError.INTERNAL_ERROR,
                    "Failed to create Collaborator client: " + e.getMessage());
        }

        // Generate payload
        CollaboratorPayload payload;
        try {
            payload = client.generatePayload(customData);
        } catch (Exception e) {
            throw new McpError(McpError.INTERNAL_ERROR,
                    "Failed to generate payload: " + e.getMessage());
        }

        // SecretKey.toString() returns the base64-encoded key
        String secretKey = client.getSecretKey().toString();

        // Store the secret key in the cache so it can be used by collaborator_interactions
        String cacheKey = "collab_" + secretKey;
        McpConfig.getRequestCache().put(cacheKey, secretKey);

        ObjectNode result = McpJson.createObjectNode();
        result.put("payload", payload.toString());
        result.put("secret_key", secretKey);
        result.put("custom_data", customData != null ? customData : "");
        return result;
    }
}
