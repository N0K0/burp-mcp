package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.mcp.util.McpJson;
import burp.mcp.util.MetricsCollector;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Returns server metrics including uptime, request counts, latencies,
 * tool call distribution, and cache hit rate.
 */
public class BurpMetricsTool implements Tool {

    private final MontoyaApi api;
    private final MetricsCollector metrics;

    public BurpMetricsTool(MontoyaApi api, MetricsCollector metrics) {
        this.api = api;
        this.metrics = metrics;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "burp_metrics",
                "Returns server metrics: uptime, request counts, latency percentiles, "
                + "per-tool call counts, cache hit rate, and active connections.",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = McpJson.createObjectNode();
        props.set("format", McpJson.property("string", "Output format: 'json' (default) or 'prometheus'", "json"));
        schema.set("properties", props);
        schema.set("required", McpJson.createArrayNode());
        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String format = args.get("format") instanceof String s ? s : "json";

        if ("prometheus".equalsIgnoreCase(format)) {
            return Map.of("content", metrics.toPrometheusFormat());
        }
        return metrics.toMetricsMap();
    }
}
