package burp.mcp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.mcp.server.McPServer;
import burp.mcp.tool.BurpMetricsTool;
import burp.mcp.tool.McpToolRegistry;
import burp.mcp.ui.McpUiPanel;
import burp.mcp.util.McpConfig;
import burp.mcp.util.ErrorLogger;
import burp.mcp.util.MetricsCollector;
import burp.mcp.util.PermissionManager;
import burp.mcp.util.RequestCache;
import burp.mcp.util.TlsManager;
import burp.mcp.util.VersionInfo;

public class BurpMcpExtension implements BurpExtension {

    private McPServer server;
    private McpToolRegistry registry;
    private MetricsCollector metrics;

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Burp MCP Server");

        // Clear stale global handler from previous loads
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {});

        logBanner(api);

        // ── Config ──
        api.logging().logToOutput("[burp-mcp] Initializing configuration...");
        McpConfig.initialize(new McpConfig.Preferences() {
            @Override public String getString(String key) {
                return api.persistence().preferences().getString(key); }
            @Override public Integer getInteger(String key) {
                String val = api.persistence().preferences().getString(key);
                return val != null ? Integer.parseInt(val) : null; }
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

        McpConfig.setRequestCache(new RequestCache(config.getCacheTtlSeconds()));

        // ── Metrics ──
        metrics = new MetricsCollector();

        // ── Tool Registry ──
        registry = new McpToolRegistry(api);
        registry.registerAllTools();
        registry.register(new BurpMetricsTool(api, metrics));
        api.logging().logToOutput("[burp-mcp] Registered " + registry.toolCount() + " tools");

        PermissionManager perms = new PermissionManager();

        // ── UI ──
        McpUiPanel uiPanel = null;
        try {
            uiPanel = new McpUiPanel(api, registry, perms);
            api.userInterface().registerSuiteTab("Burp MCP", uiPanel);
            api.logging().logToOutput("[burp-mcp] UI tab registered.");
        } catch (Exception e) {
            api.logging().logToError("[burp-mcp] UI failed (non-fatal)", e);
            ErrorLogger.log("UI/register", e);
        }

        // ── Server ──
        server = new McPServer(api, registry, config.getBindAddress(), config.getPort(), perms, metrics);
        try {
            if (config.isTlsEnabled()) {
                String mode = config.getTlsMode();
                String cn = config.getBindAddress();
                char[] pw = config.getTlsKeystorePassword().toCharArray();
                javax.net.ssl.SSLServerSocketFactory ssl;
                if ("custom".equals(mode) && !config.getTlsKeystorePath().isEmpty()) {
                    ssl = TlsManager.createFromKeystore(config.getTlsKeystorePath(), pw);
                } else {
                    ssl = TlsManager.createSelfSigned(cn, pw);
                }
                server.enableTls(ssl, "TLSv1.2", "TLSv1.3");
            }
            server.start();
            String proto = config.isTlsEnabled() ? "https" : "http";
            api.logging().logToOutput("[burp-mcp] Server started on " + proto + "://" + config.getBindAddress() + ":" + config.getPort());
            if (uiPanel != null) uiPanel.setServer(server);
        } catch (Exception e) {
            api.logging().logToError("[burp-mcp] Server start failed", e);
            ErrorLogger.log("Startup", e);
        }

        api.extension().registerUnloadingHandler(() -> {
            if (server != null) server.stop();
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
