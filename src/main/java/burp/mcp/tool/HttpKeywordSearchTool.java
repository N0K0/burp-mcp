package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.mcp.util.ByteArrayConverter;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Search for keywords across multiple HTTP responses.
 * For each keyword, checks if it appears in all response bodies (invariant) or only some (variant).
 */
public class HttpKeywordSearchTool implements Tool {

    private final MontoyaApi api;

    public HttpKeywordSearchTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "http_keyword_search",
                "Search for keywords across multiple HTTP responses and identify which keywords appear in all responses (invariant) and which appear in only some responses (variant). Provide 'keywords' (array of strings) and 'responses' (array of raw HTTP response strings).",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("keywords", McpJson.property("array", "Array of keyword strings to search for"));
        props.set("responses", McpJson.property("array", "Array of raw HTTP response strings to search within"));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        required.add("keywords");
        required.add("responses");
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        Object keywordsObj = args.get("keywords");
        Object responsesObj = args.get("responses");

        if (!(keywordsObj instanceof List)) {
            throw new McpError(McpError.INVALID_PARAMS, "'keywords' must be an array of strings");
        }
        if (!(responsesObj instanceof List)) {
            throw new McpError(McpError.INVALID_PARAMS, "'responses' must be an array of strings");
        }

        List<?> keywordsList = (List<?>) keywordsObj;
        List<?> responsesList = (List<?>) responsesObj;

        if (keywordsList.isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS, "'keywords' must not be empty");
        }
        if (responsesList.isEmpty()) {
            throw new McpError(McpError.INVALID_PARAMS, "'responses' must not be empty");
        }

        List<String> keywords = new ArrayList<>();
        for (Object kw : keywordsList) {
            if (!(kw instanceof String)) {
                throw new McpError(McpError.INVALID_PARAMS, "All items in 'keywords' must be strings");
            }
            keywords.add((String) kw);
        }

        // Parse responses
        List<HttpResponse> responses = new ArrayList<>();
        for (int i = 0; i < responsesList.size(); i++) {
            Object respObj = responsesList.get(i);
            if (!(respObj instanceof String)) {
                throw new McpError(McpError.INVALID_PARAMS,
                        "All items in 'responses' must be strings (index " + i + ")");
            }
            String respStr = (String) respObj;
            try {
                responses.add(HttpResponse.httpResponse(respStr));
            } catch (Exception e) {
                throw new McpError(McpError.INVALID_PARAMS,
                        "Failed to parse response at index " + i + ": " + e.getMessage());
            }
        }

        // Extract body strings from each response
        List<String> bodyStrings = new ArrayList<>();
        for (HttpResponse resp : responses) {
            bodyStrings.add(ByteArrayConverter.bytesToString(resp.body().getBytes()));
        }

        ObjectNode result = McpJson.createObjectNode();

        // For each keyword, check presence across all response bodies
        ArrayNode variantArray = (ArrayNode) com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        ArrayNode invariantArray = (ArrayNode) com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        ArrayNode absentArray = (ArrayNode) com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();

        for (String keyword : keywords) {
            ObjectNode kwNode = McpJson.createObjectNode();
            kwNode.put("keyword", keyword);

            int matchCount = 0;
            ArrayNode matchingResponses = (ArrayNode) com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();

            for (int i = 0; i < bodyStrings.size(); i++) {
                String body = bodyStrings.get(i);
                if (body != null && body.contains(keyword)) {
                    matchCount++;
                    matchingResponses.add(i);
                }
            }

            kwNode.set("matching_response_indices", matchingResponses);
            kwNode.put("match_count", matchCount);
            kwNode.put("total_responses", bodyStrings.size());

            if (matchCount == 0) {
                absentArray.add(kwNode);
            } else if (matchCount < bodyStrings.size()) {
                variantArray.add(kwNode);
            } else {
                invariantArray.add(kwNode);
            }
        }

        result.set("variant_keywords", variantArray);
        result.set("invariant_keywords", invariantArray);
        result.set("absent_keywords", absentArray);

        return result;
    }
}
