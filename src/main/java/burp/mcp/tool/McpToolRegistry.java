package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.mcp.util.ErrorLogger;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Registry of all MCP tools. Routes tool calls to implementations.
 */
public class McpToolRegistry {

    private final MontoyaApi api;
    private final Map<String, Tool> tools = new HashMap<>();

    public McpToolRegistry(MontoyaApi api) {
        this.api = api;
    }

    /**
     * Register a single tool.
     */
    public void register(Tool tool) {
        tools.put(tool.definition().name(), tool);
    }

    /**
     * Register all tools from all categories.
     */
    public void registerAllTools() {
        // Phase 0: Info
        register(new BurpInfoTool(api));

        // Phase 1: HTTP tools
        register(new HttpSendRequestTool(api));
        register(new HttpSendRequestsTool(api));
        register(new HttpBuildRequestTool(api));
        register(new HttpModifyRequestTool(api));
        register(new HttpParseRequestTool(api));
        register(new HttpParseResponseTool(api));
        register(new HttpStoreRequestTool(api));
        register(new HttpGetRequestTool(api));

        // Phase 2: Site map, proxy, scope
        register(new SitemapListTool(api));
        register(new SitemapListFilteredTool(api));
        register(new SitemapGetTool(api));
        register(new ProxyHistoryListTool(api));
        register(new ProxyHistoryGetTool(api));
        register(new ProxyWebSocketHistoryTool(api));
        register(new ProxyToggleInterceptTool(api));
        register(new ProxyInterceptStatusTool(api));
        register(new ScopeCheckTool(api));
        register(new ScopeSetTool(api));
        register(new ScopeListTool(api));

        // Phase 3: Decoder, send-to, cookie, analysis
        register(new DecoderDecodeTool(api));
        register(new DecoderEncodeTool(api));
        register(new HttpSendToRepeaterTool(api));
        register(new HttpSendToIntruderTool(api));
        register(new HttpSendToComparerTool(api));
        register(new HttpSendToDecoderTool(api));
        register(new CookieListTool(api));
        register(new CookieSetTool(api));
        register(new HttpDiffResponsesTool(api));
        register(new HttpKeywordSearchTool(api));

        // Phase 4: Scanner (Pro only)
        register(new ScannerStartAuditTool(api));
        register(new ScannerStartCrawlTool(api));
        register(new ScannerIssuesListTool(api));
        register(new ScannerIssuesListFilteredTool(api));
        register(new ScannerGetIssueTool(api));
        register(new ScannerGenerateReportTool(api));

        // Phase 5: Collaborator (Pro only), config, logger
        register(new CollaboratorGeneratePayloadTool(api));
        register(new CollaboratorInteractionsTool(api));
        register(new ConfigGetTool(api));
        register(new ConfigSetTool(api));
        register(new LoggerAddTool(api));

        // v1.1: New tools
        register(new ConfigListPreferencesTool(api));
        register(new SitemapSearchTool(api));

        // v1.2: WebSocket + proxy management
        register(new WebSocketHistoryGetTool(api));
    }

    /**
     * Return the number of registered tools.
     */
    public int toolCount() {
        return tools.size();
    }

    /**
     * Return the names of all registered tools.
     */
    public java.util.Set<String> getToolNames() {
        return tools.keySet();
    }

    /**
     * Get a specific tool by name. Returns null if not found.
     */
    public Tool getTool(String name) {
        Tool t = tools.get(name);
        if (t == null) t = tools.get(resolveAlias(name));
        return t;
    }

    /**
     * Resolve common user-facing aliases to canonical tool names.
     */
    private String resolveAlias(String name) {
        return switch (name) {
            case "proxy_list" -> "proxy_history_list";
            case "proxy_get" -> "proxy_history_get";
            case "sitemap_url" -> "sitemap_get";
            case "send_request" -> "http_send_request";
            case "send_to_repeater" -> "http_send_to_repeater";
            default -> name;
        };
    }

    /**
     * List all registered tools as JSON-compatible Maps.
     */
    public List<Map<String, Object>> listTools() {
        return tools.values().stream()
                .map(tool -> {
                    Map<String, Object> def = new LinkedHashMap<>();
                    def.put("name", tool.definition().name());
                    def.put("description", tool.definition().description());
                    def.put("inputSchema", tool.inputSchema());
                    return def;
                })
                .collect(Collectors.toList());
    }

    /**
     * Call a tool by name with the given arguments.
     * Returns the tool result as an Object (JSON-serializable).
     */
    public Object callTool(String name, Map<String, Object> args) throws McpError {
        String canonicalName = resolveAlias(name);
        boolean isAlias = !canonicalName.equals(name);
        Tool tool = tools.get(canonicalName);
        if (tool == null) {
            throw new McpError(McpError.METHOD_NOT_FOUND, "Unknown tool: " + name);
        }

        if (isAlias) {
            api.logging().logToOutput("[burp-mcp] ALIAS | " + name + " → " + canonicalName);
        }
        try {
            return tool.execute(args);
        } catch (McpError e) {
            // Log McpErrors too — they often wrap original exceptions from tools
            api.logging().logToError("[burp-mcp] Tool '" + name + "' error", e);
            ErrorLogger.log("Tool:" + name, e);
            throw e;
        } catch (Exception e) {
            api.logging().logToError("[burp-mcp] Tool '" + name + "' failed", e);
            ErrorLogger.log("Tool:" + name, e);
            throw new McpError(McpError.INTERNAL_ERROR, "Tool execution failed: " + e.getMessage());
        }
    }
}
