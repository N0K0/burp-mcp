package burp.mcp.util;

import java.util.Objects;

/**
 * Configuration manager backed by Montoya Preferences API.
 * Thread-safe singleton that handles null defaults.
 */
public class McpConfig {

    private static volatile RequestCache requestCache;

    public interface Preferences {
        String getString(String key);
        Integer getInteger(String key);
        void setString(String key, String value);
        void setInteger(String key, Integer value);
    }

    private static volatile McpConfig instance;
    private final Preferences preferences;

    // Default values
    private static final int DEFAULT_PORT = 4444;
    private static final int DEFAULT_MAX_RESPONSE_BYTES = 100_000;
    private static final int DEFAULT_MAX_SITEMAP_ENTRIES = 500;
    private static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";
    private static final int DEFAULT_THREAD_POOL_SIZE = 10;
    private static final int DEFAULT_REQUEST_TIMEOUT_MS = 30_000;
    private static final int DEFAULT_CACHE_TTL_SECONDS = 300;
    private static final int DEFAULT_RATE_LIMIT = 100;

    private McpConfig(Preferences preferences) {
        this.preferences = preferences;
    }

    public static synchronized McpConfig initialize(Preferences preferences) {
        if (instance == null) {
            instance = new McpConfig(preferences);
        }
        return instance;
    }

    public static McpConfig getInstance() {
        return instance;
    }

    // ── Environment variable overrides ──

    // Documented overrides (see README): BURP_MCP_PORT, BURP_MCP_BIND_ADDRESS,
    // BURP_MCP_AUTH_TOKEN, BURP_MCP_LOG_LEVEL, BURP_MCP_SOCKET_PATH. Env wins over stored prefs
    // so containers and CI can configure without touching Burp preferences.

    private static String envOrDefault(String key, String defaultVal) {
        return System.getenv().getOrDefault(key, defaultVal);
    }

    private static Integer envIntOrNull(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public int getPort() {
        Integer env = envIntOrNull("BURP_MCP_PORT");
        if (env != null) {
            return env;
        }
        Integer val = preferences.getInteger("mcp_port");
        return Objects.requireNonNullElse(val, DEFAULT_PORT);
    }

    public int getMaxResponseBodyBytes() {
        Integer val = preferences.getInteger("max_response_body_bytes");
        return Objects.requireNonNullElse(val, DEFAULT_MAX_RESPONSE_BYTES);
    }

    public int getMaxSitemapEntries() {
        Integer val = preferences.getInteger("max_sitemap_entries");
        return Objects.requireNonNullElse(val, DEFAULT_MAX_SITEMAP_ENTRIES);
    }

    public String getBindAddress() {
        String env = System.getenv("BURP_MCP_BIND_ADDRESS");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return Objects.requireNonNullElse(
                preferences.getString("bind_address"),
                DEFAULT_BIND_ADDRESS
        );
    }

    public int getThreadPoolSize() {
        Integer val = preferences.getInteger("thread_pool_size");
        return Objects.requireNonNullElse(val, DEFAULT_THREAD_POOL_SIZE);
    }

    public int getRequestTimeoutMs() {
        Integer val = preferences.getInteger("request_timeout_ms");
        return Objects.requireNonNullElse(val, DEFAULT_REQUEST_TIMEOUT_MS);
    }

    public int getCacheTtlSeconds() {
        Integer val = preferences.getInteger("cache_ttl_seconds");
        return Objects.requireNonNullElse(val, DEFAULT_CACHE_TTL_SECONDS);
    }

    public int getRateLimitPerMinute() {
        Integer val = preferences.getInteger("rate_limit_per_minute");
        return Objects.requireNonNullElse(val, DEFAULT_RATE_LIMIT);
    }

    public boolean isIncludeRequestBody() {
        String val = preferences.getString("include_request_body");
        return val == null || Boolean.parseBoolean(val);
    }

    public boolean isIncludeResponseBody() {
        String val = preferences.getString("include_response_body");
        return val == null || Boolean.parseBoolean(val);
    }

    public void setPort(int port) {
        preferences.setInteger("mcp_port", port);
    }

    public void setMaxResponseBodyBytes(int bytes) {
        preferences.setInteger("max_response_body_bytes", bytes);
    }

    public void setBindAddress(String address) {
        preferences.setString("bind_address", address);
    }

    public void setMaxSitemapEntries(int entries) {
        preferences.setInteger("max_sitemap_entries", entries);
    }

    public void setRequestTimeoutMs(int ms) {
        preferences.setInteger("request_timeout_ms", ms);
    }

    public void setCacheTtlSeconds(int seconds) {
        preferences.setInteger("cache_ttl_seconds", seconds);
    }

    public void setRateLimitPerMinute(int limit) {
        preferences.setInteger("rate_limit_per_minute", limit);
    }

    public void setThreadPoolSize(int size) {
        preferences.setInteger("thread_pool_size", size);
    }

    public void setIncludeRequestBody(boolean include) {
        preferences.setString("include_request_body", String.valueOf(include));
    }

    public void setIncludeResponseBody(boolean include) {
        preferences.setString("include_response_body", String.valueOf(include));
    }

    // ── Logging & Observability ──────────────────────────────────────

    private static final String DEFAULT_LOG_LEVEL = "INFO";
    private static final String DEFAULT_LOGGING_FILE_PATH = "";
    private static final int DEFAULT_MAX_QUEUE_SIZE = 100;
    private static final int DEFAULT_MAX_CONNECTIONS_PER_IP = 10;

    public String getLogLevel() {
        String env = System.getenv("BURP_MCP_LOG_LEVEL");
        if (env != null && !env.isBlank()) {
            return env.trim().toUpperCase(java.util.Locale.ROOT);
        }
        String val = preferences.getString("log_level");
        return val != null ? val.toUpperCase(java.util.Locale.ROOT) : DEFAULT_LOG_LEVEL;
    }

    public void setLogLevel(String level) {
        preferences.setString("log_level", level);
    }

    public String getLoggingFilePath() {
        return Objects.requireNonNullElse(preferences.getString("logging_file_path"), DEFAULT_LOGGING_FILE_PATH);
    }

    public void setLoggingFilePath(String path) {
        preferences.setString("logging_file_path", path);
    }

    public int getMaxQueueSize() {
        Integer val = preferences.getInteger("max_queue_size");
        return Objects.requireNonNullElse(val, DEFAULT_MAX_QUEUE_SIZE);
    }

    public void setMaxQueueSize(int size) {
        preferences.setInteger("max_queue_size", size);
    }

    public int getMaxConnectionsPerIp() {
        Integer val = preferences.getInteger("max_connections_per_ip");
        return Objects.requireNonNullElse(val, DEFAULT_MAX_CONNECTIONS_PER_IP);
    }

    public void setMaxConnectionsPerIp(int n) {
        preferences.setInteger("max_connections_per_ip", n);
    }

    public boolean isMetricsEnabled() {
        String val = preferences.getString("metrics_enabled");
        return val == null || Boolean.parseBoolean(val);
    }

    public void setMetricsEnabled(boolean enabled) {
        preferences.setString("metrics_enabled", String.valueOf(enabled));
    }

    public boolean isCacheEnabled() {
        String val = preferences.getString("cache_enabled");
        return val == null || Boolean.parseBoolean(val);
    }

    public void setCacheEnabled(boolean enabled) {
        preferences.setString("cache_enabled", String.valueOf(enabled));
    }

    public boolean isAuditLogEnabled() {
        String val = preferences.getString("audit_log_enabled");
        return val != null && Boolean.parseBoolean(val);
    }

    public void setAuditLogEnabled(boolean enabled) {
        preferences.setString("audit_log_enabled", String.valueOf(enabled));
    }

    // ── Authentication ──────────────────────────────────────────────

    public boolean isAuthEnabled() {
        String val = preferences.getString("auth_enabled");
        return val != null && Boolean.parseBoolean(val);
    }

    public String getAuthToken() {
        String env = System.getenv("BURP_MCP_AUTH_TOKEN");
        if (env != null && !env.isEmpty()) {
            return env;
        }
        String val = preferences.getString("auth_token");
        return val != null ? val : "";
    }

    public void setAuthEnabled(boolean enabled) {
        preferences.setString("auth_enabled", String.valueOf(enabled));
    }

    public void setAuthToken(String token) {
        preferences.setString("auth_token", token);
    }

    /**
     * Generate a cryptographically random 32-character hex API token.
     */
    public static String generateToken() {
        java.security.SecureRandom rng = new java.security.SecureRandom();
        byte[] bytes = new byte[16];
        rng.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ── TLS / HTTPS ─────────────────────────────────────────────────

    private static final String DEFAULT_TLS_KEYSTORE_PASSWORD = "burpmcp";

    public boolean isTlsEnabled() {
        String val = preferences.getString("tls_enabled");
        return val != null && Boolean.parseBoolean(val);
    }

    /** "self_signed" or "custom" */
    public String getTlsMode() {
        String val = preferences.getString("tls_mode");
        return val != null ? val : "self_signed";
    }

    public String getTlsKeystorePath() {
        return Objects.requireNonNullElse(preferences.getString("tls_keystore_path"), "");
    }

    public String getTlsKeystorePassword() {
        String val = preferences.getString("tls_keystore_password");
        return val != null ? val : DEFAULT_TLS_KEYSTORE_PASSWORD;
    }

    public void setTlsEnabled(boolean enabled) {
        preferences.setString("tls_enabled", String.valueOf(enabled));
    }

    public void setTlsMode(String mode) {
        preferences.setString("tls_mode", mode);
    }

    public void setTlsKeystorePath(String path) {
        preferences.setString("tls_keystore_path", path);
    }

    public void setTlsKeystorePassword(String password) {
        preferences.setString("tls_keystore_password", password);
    }

    // ── Unix domain socket ────────────────────────────────────────
    // Local-socket transport alongside TCP. The default path sits next to
    // the .burp project file (same directory, same basename, .sock), so the
    // socket is easy to find and multiple Burp projects never clash on ports.
    // Temporary projects, unresolvable project files, and over-long paths
    // fall back to a stable per-project socket in the temp dir.

    public boolean isSocketEnabled() {
        String val = preferences.getString("socket_enabled");
        return val == null || Boolean.parseBoolean(val);
    }

    /**
     * Explicit socket path, or "" for automatic per-project path.
     * {@code BURP_MCP_SOCKET_PATH} env var wins over stored prefs.
     */
    public String getSocketPath() {
        String env = System.getenv("BURP_MCP_SOCKET_PATH");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        String val = preferences.getString("socket_path");
        return val != null ? val : "";
    }

    public void setSocketEnabled(boolean enabled) {
        preferences.setString("socket_enabled", String.valueOf(enabled));
    }

    public void setSocketPath(String path) {
        preferences.setString("socket_path", path != null ? path : "");
    }

    /**
     * Automatic socket path for a project tag (project id or similar).
     * Short hashed name keeps well under OS socket-path limits.
     */
    public static java.nio.file.Path defaultSocketPath(String tag) {
        String safe = shortTag(tag);
        return java.nio.file.Paths.get(
                System.getProperty("java.io.tmpdir"), "burp-mcp-" + safe + ".sock");
    }

    /**
     * Resolve the effective socket path: an explicit config wins, otherwise a
     * socket next to the current project file, otherwise a stable temporary
     * per-project path.
     */
    public java.nio.file.Path resolveSocketPath(String projectName, String projectId) {
        return resolveSocketPath(projectName, projectId, ProjectFiles.burpPrefLookup());
    }

    /** Test seam: the same resolution against an injected preference lookup. */
    java.nio.file.Path resolveSocketPath(String projectName, String projectId,
                                         java.util.function.Function<String, String> prefLookup) {
        String configured = getSocketPath();
        if (configured != null && !configured.isBlank()) {
            return java.nio.file.Paths.get(configured.trim());
        }
        java.util.Optional<java.nio.file.Path> projectFile =
                ProjectFiles.locate(projectName, prefLookup);
        if (projectFile.isPresent()) {
            java.nio.file.Path sibling = ProjectFiles.socketPathFor(projectFile.get());
            if (ProjectFiles.isUsableSocketPath(sibling)) {
                return sibling;
            }
        }
        String tag = (projectId != null && !projectId.isBlank())
                ? projectId : "port-" + getPort();
        return defaultSocketPath(tag);
    }

    private static String shortTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return "default";
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(tag.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return String.valueOf(Math.abs(tag.hashCode()));
        }
    }

    // ── Active socket discovery ───────────────────────────────────
    // Set by the extension on successful socket bind so tools
    // (burp_info) and agents can discover the live socket path.

    private static volatile String activeSocketPath;

    public static void setActiveSocketPath(String path) {
        activeSocketPath = path;
    }

    public static String getActiveSocketPath() {
        return activeSocketPath;
    }

    public static synchronized RequestCache getRequestCache() {
        if (requestCache == null) {
            throw new IllegalStateException("RequestCache not initialized. Ensure McpConfig is initialized first.");
        }
        return requestCache;
    }

    public static synchronized void setRequestCache(RequestCache cache) {
        requestCache = cache;
    }

    // ── Validation ──

    /**
     * Validate all configuration values. Returns a list of error messages.
     * Empty list means valid.
     */
    public java.util.List<String> validate() {
        java.util.List<String> errors = new java.util.ArrayList<>();
        int port = getPort();
        if (port < 1 || port > 65535) errors.add("Port must be 1-65535, got " + port);
        if (getThreadPoolSize() < 1 || getThreadPoolSize() > 50) errors.add("Thread pool size must be 1-50");
        if (getMaxQueueSize() < 1 || getMaxQueueSize() > 1000) errors.add("Max queue size must be 1-1000");
        if (getMaxResponseBodyBytes() < 1000 || getMaxResponseBodyBytes() > 100_000_000) errors.add("Max response body bytes must be 1000-100,000,000");
        if (getMaxSitemapEntries() <= 0) errors.add("Max sitemap entries must be > 0");
        if (getRequestTimeoutMs() < 1000 || getRequestTimeoutMs() > 300_000) errors.add("Request timeout must be 1000-300,000ms");
        if (getCacheTtlSeconds() < 0 || getCacheTtlSeconds() > 86400) errors.add("Cache TTL must be 0-86400 seconds");
        if (getRateLimitPerMinute() < 0 || getRateLimitPerMinute() > 10000) errors.add("Rate limit must be 0-10000 per minute");
        if (getMaxConnectionsPerIp() < 1 || getMaxConnectionsPerIp() > 100) errors.add("Max connections per IP must be 1-100");
        String bind = getBindAddress();
        if (bind == null || bind.isBlank()) errors.add("Bind address must not be empty");
        String logLevel = getLogLevel();
        if (!java.util.Set.of("DEBUG", "INFO", "WARN", "ERROR").contains(logLevel)) errors.add("Log level must be DEBUG/INFO/WARN/ERROR, got " + logLevel);
        if (isAuthEnabled() && getAuthToken().isEmpty()) errors.add("Auth token must not be empty when auth is enabled");
        String tlsMode = getTlsMode();
        if (!java.util.Set.of("self_signed", "custom").contains(tlsMode)) errors.add("TLS mode must be self_signed or custom, got " + tlsMode);
        if (isTlsEnabled() && "custom".equals(tlsMode) && getTlsKeystorePath().isEmpty()) errors.add("TLS keystore path must not be empty in custom mode");
        String logPath = getLoggingFilePath();
        if (logPath != null && logPath.contains("..")) errors.add("Logging file path must not contain '..'");
        if (isSocketEnabled()) {
            String sp = getSocketPath();
            if (sp != null && !sp.isBlank() && sp.length() > 100) {
                errors.add("Socket path must be < 100 chars (OS socket-path limit), got " + sp.length());
            }
        }
        return errors;
    }
}
