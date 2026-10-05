package burp.mcp.server;

import burp.api.montoya.MontoyaApi;
import burp.mcp.tool.McpToolRegistry;
import burp.mcp.util.ApprovalManager;
import burp.mcp.util.ErrorLogger;
import burp.mcp.util.McpConfig;
import burp.mcp.util.MetricsCollector;
import burp.mcp.util.PermissionManager;
import burp.mcp.util.RequestCache;
import burp.mcp.util.TlsManager;

import javax.net.ssl.SSLServerSocketFactory;
import java.net.BindException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Owns the MCP listeners' lifecycle so they can be started, stopped, and
 * restarted from the UI without reloading the extension.
 *
 * <p>TCP and the Unix socket are bound independently: a busy TCP port
 * (another Burp instance still holding 4444) records a clear error and lets
 * the socket serve this project instead of aborting the whole startup. A
 * restart builds a fresh {@link McPServer} — the worker pool is shut down on
 * stop — and re-reads saved preferences, so port, bind address, TLS, socket,
 * and pool changes apply live.
 */
public class McpServerManager {

    private final MontoyaApi api;
    private final McpToolRegistry registry;
    private final PermissionManager permissions;
    private final MetricsCollector metrics;
    private final ApprovalManager approvalManager;
    private final AccessGate accessGate;

    private volatile McPServer server;
    private volatile UnixSocketServer socketServer;
    private volatile String tcpError;
    private volatile String socketError;
    private volatile Path socketPath;
    private volatile Consumer<String> logListener;
    private final CopyOnWriteArrayList<Runnable> stateListeners = new CopyOnWriteArrayList<>();

    public McpServerManager(MontoyaApi api, McpToolRegistry registry,
                            PermissionManager permissions, MetricsCollector metrics) {
        this.api = api;
        this.registry = registry;
        this.permissions = permissions;
        this.metrics = metrics;
        this.approvalManager = new ApprovalManager();
        this.accessGate = new AccessGate(api, permissions, approvalManager);
        McpConfig.setApprovalManager(approvalManager);
    }

    // ── Lifecycle ────────────────────────────────────────────────

    public synchronized void start() {
        if (isRunning()) {
            // Defensive: never leak an existing listener if Start is called
            // while something is already bound.
            stop();
        }
        McpConfig config = McpConfig.getInstance();
        if (metrics != null) {
            metrics.setEnabled(config.isMetricsEnabled());
            metrics.resetUptime();
        }
        reloadCaches(config);

        McPServer srv = new McPServer(api, registry, config.getBindAddress(),
                config.getPort(), permissions, metrics, accessGate);
        srv.setLogListener(logListener);

        tcpError = null;
        try {
            configureTls(srv, config);
            srv.start();
            String proto = config.isTlsEnabled() ? "https" : "http";
            log("[burp-mcp] TCP listening on " + proto + "://"
                    + config.getBindAddress() + ":" + config.getPort());
        } catch (Exception e) {
            tcpError = describeTcpError(e, config.getPort());
            log("[burp-mcp] TCP listener failed: " + tcpError + " — serving on the Unix socket only");
            logError("Startup", e);
            try {
                srv.stop();
            } catch (Exception ignored) {
            }
        }
        server = srv;

        socketError = null;
        socketPath = null;
        if (config.isSocketEnabled()) {
            startSocket(srv, config);
        } else {
            McpConfig.setActiveSocketPath(null);
        }
        fireStateChanged();
    }

    public synchronized void stop() {
        UnixSocketServer uds = socketServer;
        if (uds != null) {
            try {
                uds.stop();
            } catch (Exception e) {
                logError("Shutdown/socket", e);
            }
            socketServer = null;
        }
        McpConfig.setActiveSocketPath(null);
        socketPath = null;
        socketError = null;

        McPServer srv = server;
        if (srv != null) {
            try {
                srv.setLogListener(null);
                srv.stop();
            } catch (Exception e) {
                logError("Shutdown", e);
            }
            server = null;
        }
        tcpError = null;
        log("[burp-mcp] MCP listeners stopped");
        fireStateChanged();
    }

    public synchronized void restart() {
        stop();
        start();
    }

    // ── State ────────────────────────────────────────────────────

    public McPServer getServer() {
        return server;
    }

    public MetricsCollector getMetrics() {
        return metrics;
    }

    public ApprovalManager getApprovalManager() {
        return approvalManager;
    }

    public AccessGate getAccessGate() {
        return accessGate;
    }

    /** TCP serving, i.e. the port was bound successfully. */
    public boolean isTcpRunning() {
        McPServer srv = server;
        return srv != null && tcpError == null && srv.isRunning();
    }

    /** Unix socket serving this project. */
    public boolean isSocketRunning() {
        UnixSocketServer uds = socketServer;
        return uds != null && uds.isRunning();
    }

    public boolean isRunning() {
        return isTcpRunning() || isSocketRunning();
    }

    public String getTcpError() {
        return tcpError;
    }

    public String getSocketError() {
        return socketError;
    }

    public Path getSocketPath() {
        UnixSocketServer uds = socketServer;
        return uds != null && uds.isRunning() ? socketPath : null;
    }

    public String getEndpoint() {
        McpConfig config = McpConfig.getInstance();
        return config.getBindAddress() + ":" + config.getPort();
    }

    /** Forwarded to every server this manager creates. */
    public void setLogListener(Consumer<String> listener) {
        this.logListener = listener;
        McPServer srv = server;
        if (srv != null) {
            srv.setLogListener(listener);
        }
    }

    public void addStateListener(Runnable listener) {
        if (listener != null) {
            stateListeners.add(listener);
        }
    }

    // ── Internals ────────────────────────────────────────────────

    private void startSocket(McPServer srv, McpConfig config) {
        String projectName = null;
        String projectId = null;
        try {
            if (api.project() != null) {
                projectName = api.project().name();
                projectId = api.project().id();
            }
        } catch (Exception ignored) {
            // Project unavailable (e.g. mocked API) — fall back to a temp path.
        }
        try {
            Path path = config.resolveSocketPath(projectName, projectId);
            UnixSocketServer uds = new UnixSocketServer(srv, path,
                    config.getThreadPoolSize(), this::log);
            uds.start();
            socketServer = uds;
            socketPath = path;
            McpConfig.setActiveSocketPath(path.toString());
            log("[burp-mcp] Unix socket listening on " + path);
        } catch (Exception e) {
            socketError = e.getMessage() != null ? e.getMessage() : e.toString();
            log("[burp-mcp] Unix socket failed: " + socketError);
            logError("Startup/socket", e);
        }
    }

    private void configureTls(McPServer srv, McpConfig config) throws Exception {
        if (!config.isTlsEnabled()) {
            return;
        }
        String mode = config.getTlsMode();
        char[] password = config.getTlsKeystorePassword().toCharArray();
        try {
            SSLServerSocketFactory ssl;
            if ("custom".equals(mode)) {
                if (config.getTlsKeystorePath().isEmpty()) {
                    throw new IllegalStateException("tls_mode=custom requires tls_keystore_path");
                }
                ssl = TlsManager.createFromKeystore(config.getTlsKeystorePath(), password);
            } else if (!"self_signed".equals(mode)) {
                throw new IllegalStateException("Unknown tls_mode: " + mode);
            } else {
                ssl = TlsManager.createSelfSigned(config.getBindAddress(), password);
            }
            srv.enableTls(ssl, "TLSv1.2", "TLSv1.3");
        } finally {
            // Clear the password copy as soon as possible.
            Arrays.fill(password, '\0');
        }
    }

    private static void reloadCaches(McpConfig config) {
        try {
            McpConfig.setRequestCache(new RequestCache(config.getCacheTtlSeconds()));
            McpConfig.getRequestCache().setEnabled(config.isCacheEnabled());
        } catch (Exception e) {
            ErrorLogger.log("Startup/cache", e);
        }
    }

    /** Turn a bind failure into a one-line, user-facing reason. */
    static String describeTcpError(Exception e, int port) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof BindException) {
                return "port " + port + " already in use (another Burp instance?)";
            }
            cause = cause.getCause();
        }
        String message = e.getMessage();
        return message != null && !message.isBlank() ? message : e.getClass().getSimpleName();
    }

    private void fireStateChanged() {
        for (Runnable listener : stateListeners) {
            try {
                listener.run();
            } catch (Exception ignored) {
                // A broken UI listener must never break the lifecycle.
            }
        }
    }

    private void log(String message) {
        try {
            api.logging().logToOutput(message);
        } catch (Exception ignored) {
        }
    }

    private void logError(String context, Throwable t) {
        try {
            api.logging().logToError("[burp-mcp] " + context, t);
        } catch (Exception ignored) {
        }
        ErrorLogger.log(context, t);
    }
}
