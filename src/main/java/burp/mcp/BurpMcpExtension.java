package burp.mcp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.mcp.server.McpServerManager;
import burp.mcp.tool.BurpMetricsTool;
import burp.mcp.tool.McpToolRegistry;
import burp.mcp.ui.McpUiPanel;
import burp.mcp.util.McpConfig;
import burp.mcp.util.ErrorLogger;
import burp.mcp.util.MetricsCollector;
import burp.mcp.util.PermissionManager;
import burp.mcp.util.RequestCache;
import burp.mcp.util.VersionInfo;

public class BurpMcpExtension implements BurpExtension {

    private McpServerManager serverManager;
    private McpToolRegistry registry;
    private MetricsCollector metrics;
    private McpUiPanel uiPanel;

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Burp MCP Server");

        // Clear stale global handler from previous loads, but keep logging
        // so post-reload crashes are still visible. After unload the Montoya
        // proxies are dead, so logging itself can throw — never let the
        // handler mask the original exception.
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                api.logging().logToError("[burp-mcp] uncaught in thread " + t.getName(), e);
            } catch (Throwable ignored) {
                try {
                    System.err.println("[burp-mcp] uncaught in thread " + t.getName() + ": " + e);
                } catch (Throwable ignoredAgain) {
                    // Nothing else we can safely do.
                }
            }
        });

        logBanner(api);

        // ── Config ──
        api.logging().logToOutput("[burp-mcp] Initializing configuration...");
        McpConfig.initialize(new McpConfig.Preferences() {
            @Override public String getString(String key) {
                return api.persistence().preferences().getString(key); }
            @Override public Integer getInteger(String key) {
                String val = api.persistence().preferences().getString(key);
                if (val == null) {
                    return null;
                }
                try {
                    return Integer.parseInt(val.trim());
                } catch (NumberFormatException e) {
                    api.logging().logToError("[burp-mcp] Ignoring corrupt preference '" + key + "': " + val, e);
                    return null;
                }
            }
            @Override public void setString(String key, String value) {
                api.persistence().preferences().setString(key, value); }
            @Override public void setInteger(String key, Integer value) {
                api.persistence().preferences().setString(key, String.valueOf(value)); }
        });
        McpConfig config = McpConfig.getInstance();

        // Apply logging file path from config
        String logPath = config.getLoggingFilePath();
        if (logPath != null && !logPath.isEmpty()) {
            ErrorLogger.setLogPath(logPath);
        }

        api.logging().logToOutput("[burp-mcp] Port: " + config.getPort());
        api.logging().logToOutput("[burp-mcp] Log level: " + config.getLogLevel());

        for (String err : config.validate()) {
            api.logging().logToOutput("[burp-mcp] Invalid config: " + err);
        }

        McpConfig.setRequestCache(new RequestCache(config.getCacheTtlSeconds()));
        McpConfig.getRequestCache().setEnabled(config.isCacheEnabled());

        // ── Metrics ──
        metrics = new MetricsCollector();
        metrics.setEnabled(config.isMetricsEnabled());

        // ── Tool Registry ──
        registry = new McpToolRegistry(api);
        registry.registerAllTools();
        registry.register(new BurpMetricsTool(api, metrics));
        api.logging().logToOutput("[burp-mcp] Registered " + registry.toolCount() + " tools");

        PermissionManager perms = new PermissionManager();

        // ── Server lifecycle (start/stop/restart also available in the UI) ──
        serverManager = new McpServerManager(api, registry, perms, metrics);

        // ── UI ──
        try {
            uiPanel = new McpUiPanel(api, registry, perms, serverManager);
            api.userInterface().registerSuiteTab("Burp MCP", uiPanel);
            api.logging().logToOutput("[burp-mcp] UI tab registered.");
        } catch (Exception e) {
            api.logging().logToError("[burp-mcp] UI failed (non-fatal)", e);
            ErrorLogger.log("UI/register", e);
        }

        serverManager.start();

        api.extension().registerUnloadingHandler(() -> {
            try {
                if (serverManager != null) {
                    serverManager.stop();
                }
            } finally {
                // Stop Swing timers and detach listeners so a reload cannot
                // leave this instance's UI polling a dead Montoya API.
                if (uiPanel != null) {
                    try {
                        uiPanel.dispose();
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    private void logBanner(MontoyaApi api) {
        api.logging().logToOutput("========================================");
        api.logging().logToOutput("  Burp MCP Server v" + VersionInfo.getFullVersion());
        api.logging().logToOutput("  Java: " + System.getProperty("java.version"));
        api.logging().logToOutput("  Burp: " + api.burpSuite().version().name());
        api.logging().logToOutput("========================================");
    }
}
