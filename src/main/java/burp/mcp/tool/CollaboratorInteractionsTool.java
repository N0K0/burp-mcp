package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.collaborator.CollaboratorClient;
import burp.api.montoya.collaborator.Interaction;
import burp.api.montoya.collaborator.InteractionType;
import burp.api.montoya.collaborator.SecretKey;
import burp.mcp.util.McpConfig;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Get Collaborator interactions for a previously generated payload.
 */
public class CollaboratorInteractionsTool extends ScannerBase implements Tool {

    public CollaboratorInteractionsTool(MontoyaApi api) {
        super(api);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "collaborator_interactions",
                "Get Collaborator Server interactions for a previously generated payload. Provide 'secret_key' (from collaborator_generate_payload) and optionally 'interaction_types' (array of types: DNS_A, DNS_CNAME, DNS_MX, DNS_TXT, DNS_AAAA, DNS_SRV, DNS_ANY, HTTP, SMTP). Requires Burp Suite Professional.",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("secret_key", McpJson.property("string", "Secret key from a previously generated collaborator payload"));
        props.set("interaction_types", McpJson.property("array", "Array of interaction types to filter: DNS_A, DNS_CNAME, DNS_MX, DNS_TXT, DNS_AAAA, DNS_SRV, DNS_ANY, HTTP, SMTP"));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        required.add("secret_key");
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        checkProEdition();

        String secretKey = (String) args.get("secret_key");
        if (secretKey == null || secretKey.isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS, "'secret_key' is required");
        }

        // Verify secret key exists in cache
        String cacheKey = "collab_" + secretKey;
        if (McpConfig.getRequestCache().get(cacheKey) == null) {
            throw new McpError(McpError.NOT_FOUND,
                    "Secret key not found in cache. Generate a payload first using collaborator_generate_payload.");
        }

        // Restore the client from the secret key (Montoya SecretKey, not javax.crypto)
        CollaboratorClient client;
        try {
            SecretKey secret = SecretKey.secretKey(secretKey);
            client = api.collaborator().restoreClient(secret);
        } catch (Exception e) {
            throw new McpError(McpError.INVALID_PARAMS,
                    "Invalid secret_key: " + e.getMessage());
        }

        // Parse requested interaction types for client-side filtering
        // (InteractionFilter does NOT support type filtering - only interactionIdFilter and interactionPayloadFilter)
        List<InteractionType> requestedTypes = null;
        Object typesObj = args.get("interaction_types");
        if (typesObj instanceof List && !((List<?>) typesObj).isEmpty()) {
            requestedTypes = new ArrayList<>();
            for (Object typeObj : (List<?>) typesObj) {
                if (!(typeObj instanceof String)) {
                    throw new McpError(McpError.INVALID_PARAMS,
                            "All items in 'interaction_types' must be strings");
                }
                try {
                    requestedTypes.add(InteractionType.valueOf(((String) typeObj).toUpperCase()));
                } catch (IllegalArgumentException e) {
                    throw new McpError(McpError.INVALID_PARAMS,
                            "Invalid interaction type: " + typeObj + ". Valid types: " +
                            Arrays.toString(InteractionType.values()));
                }
            }
        }

        // Get all interactions and filter client-side
        List<Interaction> allInteractions = client.getAllInteractions();
        List<Interaction> interactions = allInteractions;
        if (requestedTypes != null) {
            List<Interaction> filtered = new ArrayList<>();
            for (Interaction i : allInteractions) {
                if (requestedTypes.contains(i.type())) {
                    filtered.add(i);
                }
            }
            interactions = filtered;
        }

        // Serialize interactions
        ArrayNode result = McpJson.mapper().createArrayNode();
        for (Interaction interaction : interactions) {
            ObjectNode iNode = McpJson.createObjectNode();
            iNode.put("id", interaction.id().toString());
            iNode.put("type", interaction.type().name());
            iNode.put("clientIp", interaction.clientIp().getHostAddress());
            iNode.put("clientPort", interaction.clientPort());
            iNode.put("timeStamp", interaction.timeStamp().toString());
            iNode.put("customData", interaction.customData().orElse(null));

            // DNS details: queryType() -> DnsQueryType, query() -> ByteArray
            var dnsDetails = interaction.dnsDetails();
            if (dnsDetails.isPresent()) {
                ObjectNode dnsNode = McpJson.createObjectNode();
                var dd = dnsDetails.get();
                dnsNode.put("queryType", dd.queryType().name());
                byte[] queryBytes = dd.query().getBytes();
                StringBuilder hex = new StringBuilder();
                for (byte b : queryBytes) {
                    hex.append(String.format("%02x", b));
                }
                dnsNode.put("query", hex.toString());
                iNode.set("dnsDetails", dnsNode);
            }

            // HTTP details: protocol() -> HttpProtocol, requestResponse() -> HttpRequestResponse
            var httpDetails = interaction.httpDetails();
            if (httpDetails.isPresent()) {
                ObjectNode httpNode = McpJson.createObjectNode();
                var hd = httpDetails.get();
                httpNode.put("protocol", hd.protocol().name());
                var rr = hd.requestResponse();
                ObjectNode rrNode = McpJson.createObjectNode();
                rrNode.put("request", rr.request().toString());
                rrNode.put("response", rr.response() != null ? rr.response().toString() : null);
                httpNode.set("requestResponse", rrNode);
                iNode.set("httpDetails", httpNode);
            }

            // SMTP details: protocol() -> SmtpProtocol, conversation() -> String
            var smtpDetails = interaction.smtpDetails();
            if (smtpDetails.isPresent()) {
                ObjectNode smtpNode = McpJson.createObjectNode();
                var sd = smtpDetails.get();
                smtpNode.put("protocol", sd.protocol().name());
                smtpNode.put("conversation", sd.conversation());
                iNode.set("smtpDetails", smtpNode);
            }

            result.add(iNode);
        }

        return result;
    }
}
