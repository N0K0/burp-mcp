package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.mcp.util.McpConfig;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Returns all MCP-specific preferences and their current values.
 */
public class ConfigListPreferencesTool implements Tool {

    private final MontoyaApi api;

    public ConfigListPreferencesTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("config_list_preferences",
                "Returns all MCP server configuration preferences as a JSON object.",
                inputSchema());
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", McpJson.createObjectNode());
        schema.set("required", McpJson.createArrayNode());
        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        McpConfig cfg = McpConfig.getInstance();
        Map<String, Object> prefs = new LinkedHashMap<>();
        prefs.put("mcp_port", cfg.getPort());
        prefs.put("bind_address", cfg.getBindAddress());
        prefs.put("thread_pool_size", cfg.getThreadPoolSize());
        prefs.put("max_queue_size", cfg.getMaxQueueSize());
        prefs.put("max_response_body_bytes", cfg.getMaxResponseBodyBytes());
        prefs.put("max_sitemap_entries", cfg.getMaxSitemapEntries());
        prefs.put("request_timeout_ms", cfg.getRequestTimeoutMs());
        prefs.put("cache_ttl_seconds", cfg.getCacheTtlSeconds());
        prefs.put("rate_limit_per_minute", cfg.getRateLimitPerMinute());
        prefs.put("max_connections_per_ip", cfg.getMaxConnectionsPerIp());
        prefs.put("log_level", cfg.getLogLevel());
        prefs.put("logging_file_path", cfg.getLoggingFilePath());
        prefs.put("include_request_body", cfg.isIncludeRequestBody());
        prefs.put("include_response_body", cfg.isIncludeResponseBody());
        prefs.put("metrics_enabled", cfg.isMetricsEnabled());
        prefs.put("cache_enabled", cfg.isCacheEnabled());
        prefs.put("auth_enabled", cfg.isAuthEnabled());
        prefs.put("tls_enabled", cfg.isTlsEnabled());
        prefs.put("tls_mode", cfg.getTlsMode());
        return prefs;
    }
}
