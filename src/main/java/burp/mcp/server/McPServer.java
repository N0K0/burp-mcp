package burp.mcp.server;

import burp.api.montoya.MontoyaApi;
import burp.mcp.tool.McpToolRegistry;
import burp.mcp.util.CircuitBreaker;
import burp.mcp.util.LogEntry;
import burp.mcp.util.McpConfig;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;
import burp.mcp.util.MetricsCollector;
import burp.mcp.util.PermissionManager;
import burp.mcp.util.RateLimiter;
import burp.mcp.util.VersionInfo;

import fi.iki.elonen.NanoHTTPD;

import javax.net.ssl.SSLServerSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class McPServer extends NanoHTTPD {

    private static final String MIME_JSON = "application/json";
    private static final int MAX_BODY_SIZE = 10 * 1024 * 1024;

    private final MontoyaApi api;
    private final McpToolRegistry registry;
    private final PermissionManager permissions;
    private final MetricsCollector metrics;
    private final AtomicLong requestCount = new AtomicLong(0);
    private volatile Consumer<String> logListener;
    private final McpConfig config;

    // Rate limiting
    private volatile RateLimiter rateLimiter;
    // Circuit breakers for external-calling tools
    private final ConcurrentHashMap<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    // Per-IP connection tracking
    private final ConcurrentHashMap<String, AtomicInteger> connectionsPerIp = new ConcurrentHashMap<>();

    public McPServer(MontoyaApi api, McpToolRegistry registry, String bindAddress, int port,
                     PermissionManager permissions, MetricsCollector metrics) {
        super(bindAddress, port);
        this.api = api;
        this.registry = registry;
        this.permissions = permissions;
        this.metrics = metrics;
        this.config = McpConfig.getInstance();
    }

    public McPServer(MontoyaApi api, McpToolRegistry registry, String bindAddress, int port) {
        this(api, registry, bindAddress, port, new PermissionManager(), null);
    }

    public McPServer(MontoyaApi api, McpToolRegistry registry, String bindAddress, int port,
                     PermissionManager permissions) {
        this(api, registry, bindAddress, port, permissions, null);
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            return serveInternal(session);
        } catch (Throwable t) {
            try { api.logging().logToError("[burp-mcp] UNCATCHED", t); } catch (Exception ignored) {}
            LogEntry entry = LogEntry.create("ERROR", "McPServer:serve", null, 0, "Uncaught exception", t);
            try { burp.mcp.util.ErrorLogger.log(entry); } catch (Exception ignored) {}
            if (metrics != null) metrics.recordError();
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_JSON,
                    McpJson.buildError(McpError.INTERNAL_ERROR, "Internal server error", null, null));
        }
    }

    private Response serveInternal(IHTTPSession session) {
        long startTime = System.currentTimeMillis();
        String correlationId = UUID.randomUUID().toString().substring(0, 8);

        // ── Health check endpoint (non-JSON-RPC) ──
        if (session.getMethod() == Method.GET && "/health".equals(session.getUri())) {
            return handleHealth(session, startTime, correlationId);
        }

        if (session.getMethod() != Method.POST) {
            logStructured("WARN", "McPServer", correlationId, startTime,
                    "REJECTED: method=" + session.getMethod(), null);
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_JSON,
                    McpJson.buildError(McpError.INVALID_REQUEST, "Use POST", null, null));
        }

        // ── Auth ──
        if (config != null && config.isAuthEnabled()) {
            String ah = session.getHeaders().get("authorization");
            if (ah == null) ah = session.getHeaders().get("Authorization");
            if (ah == null || !ah.startsWith("Bearer ")) {
                logStructured("WARN", "McPServer:auth", correlationId, startTime,
                        "DENIED: missing auth", null);
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_JSON,
                        McpJson.buildError(-32005, "Authorization: Bearer *** required", null, null));
            }
            String token = ah.substring(7).trim();
            if (!token.equals(config.getAuthToken())) {
                logStructured("WARN", "McPServer:auth", correlationId, startTime,
                        "DENIED: bad token", null);
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_JSON,
                        McpJson.buildError(-32005, "Invalid token", null, null));
            }
            logStructured("DEBUG", "McPServer:auth", correlationId, startTime,
                    "AUTH OK", null);
        }

        // ── Read body ──
        String body;
        try {
            Map<String, String> headers = session.getHeaders();
            String cl = null;
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase("content-length")) { cl = e.getValue(); break; }
            }
            if (cl == null) {
                logStructured("ERROR", "McPServer", correlationId, startTime,
                        "no content-length", null);
                return badReq("Missing Content-Length");
            }
            int len = Integer.parseInt(cl.trim());
            if (len <= 0 || len > MAX_BODY_SIZE) {
                logStructured("ERROR", "McPServer", correlationId, startTime,
                        "bad body size: " + len, null);
                return badReq("Bad body size");
            }
            logStructured("DEBUG", "McPServer", correlationId, startTime,
                    "body_size=" + len, null);

            byte[] buf = new byte[len];
            int off = 0;
            InputStream is = session.getInputStream();
            while (off < len) { int n = is.read(buf, off, len - off); if (n == -1) break; off += n; }
            body = new String(buf, 0, off, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logStructured("ERROR", "McPServer", correlationId, startTime,
                    "read fail", e);
            if (metrics != null) metrics.recordError();
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_JSON,
                    McpJson.buildError(McpError.INTERNAL_ERROR, "Read error", null, null));
        }

        if (body == null || body.isEmpty()) {
            logStructured("ERROR", "McPServer", correlationId, startTime,
                    "empty body", null);
            return badReq("Empty body");
        }

        // ── Dispatch ──
        try {
            McpJson.JsonRpcRequest req = McpJson.parseRequest(body);
            if (req == null) {
                logStructured("ERROR", "McPServer", correlationId, startTime,
                        "bad json", null);
                return badReq("Invalid JSON");
            }
            String method = req.method;
            String response;

            if ("initialize".equals(method)) {
                response = McpJson.buildResponse(Map.of(
                    "protocolVersion", "2024-11-05",
                    "capabilities", Map.of("tools", Map.of()),
                    "serverInfo", Map.of("name", "burp-mcp", "version", VersionInfo.getFullVersion())
                ), req.id);
                logStructured("INFO", "McPServer", correlationId, startTime,
                        "initialize OK", null);
                notifyLogListener("[burp-mcp] INFO | initialize | " + elapsed(startTime) + "ms", "INFO");
            } else if ("tools/list".equals(method)) {
                response = McpJson.buildResponse(Map.of("tools", registry.listTools()), req.id);
                logStructured("INFO", "McPServer", correlationId, startTime,
                        "tools/list OK", null);
                notifyLogListener("[burp-mcp] INFO | tools/list | " + elapsed(startTime) + "ms", "INFO");
            } else if ("tools/call".equals(method)) {
                Map<String, Object> params = req.params;
                if (params == null) throw new McpError(McpError.INVALID_PARAMS, "Missing params");
                String toolName = (String) params.get("name");

                // ── Rate limiting (tools/call only) ──
                if (config != null) {
                    if (rateLimiter == null) rateLimiter = new RateLimiter(config.getRateLimitPerMinute());
                    String ip = extractClientIp(session);
                    long waitSec = rateLimiter.tryConsume(ip);
                    if (waitSec > 0) {
                        logStructured("WARN", "McPServer:ratelimit", correlationId, startTime,
                                "RATE LIMITED: " + toolName + " ip=" + ip, null);
                        if (metrics != null) metrics.recordRateLimited();
                        return newFixedLengthResponse(Response.Status.TOO_MANY_REQUESTS, MIME_JSON,
                                McpJson.buildError(McpError.RATE_LIMITED,
                                        "Rate limit exceeded. Try again in " + waitSec + "s", null, null));
                    }
                }

                // ── Permission check ──
                String deny = permissions.checkAccess(toolName);
                if (deny != null) {
                    logStructured("WARN", "McPServer", correlationId, startTime,
                            "PERM DENIED: " + toolName + " — " + deny, null);
                    throw new McpError(McpError.PERMISSION_DENIED, deny);
                }

                // Permission gate outcome (DEBUG)
                logStructured("DEBUG", "McPServer", correlationId, startTime,
                        "perm gate: " + toolName + " ALLOWED", null);

                @SuppressWarnings("unchecked")
                Map<String, Object> toolArgs = (Map<String, Object>) params.get("arguments");
                if (toolArgs == null) toolArgs = java.util.Collections.emptyMap();

                // ── Circuit breaker for external tools ──
                String cbCheck = checkCircuitBreaker(toolName, correlationId, startTime);
                if (cbCheck != null) {
                    throw new McpError(McpError.REQUEST_FAILED, cbCheck);
                }

                logStructured("DEBUG", "McPServer", correlationId, startTime,
                        "tools/call " + toolName + " params=" + summarizeParams(toolArgs), null);

                Object toolResult;
                try {
                    toolResult = registry.callTool(toolName, toolArgs);
                    recordCircuitBreakerSuccess(toolName);
                } catch (McpError e) {
                    recordCircuitBreakerFailure(toolName);
                    throw e;
                } catch (Exception e) {
                    recordCircuitBreakerFailure(toolName);
                    // Translate specific exceptions
                    Throwable cause = e.getCause();
                    if (cause instanceof ConnectException) {
                        throw new McpError(McpError.REQUEST_FAILED, "Connection refused: " + sanitizeMessage(e));
                    } else if (cause instanceof SocketTimeoutException) {
                        throw new McpError(McpError.REQUEST_FAILED, "Request timed out: " + sanitizeMessage(e));
                    } else if (cause instanceof UnknownHostException) {
                        throw new McpError(McpError.REQUEST_FAILED, "DNS resolution failed: " + sanitizeMessage(e));
                    } else if (e instanceof IllegalArgumentException) {
                        throw new McpError(McpError.INVALID_PARAMS, "Invalid parameter: " + sanitizeMessage(e));
                    }
                    throw e;
                }

                if (metrics != null) metrics.recordToolCall(toolName);

                response = McpJson.buildResponse(Map.of("content",
                    java.util.List.of(Map.of("type", "text", "text",
                        toolResult instanceof String ? (String) toolResult : McpJson.toJson(toolResult)))), req.id);
                logStructured("INFO", "McPServer", correlationId, startTime,
                        "tools/call " + toolName + " OK", null);
                notifyLogListener("[burp-mcp] INFO | tools/call " + toolName + " | " + elapsed(startTime) + "ms", "INFO");
            } else if ("notifications/initialized".equals(method)) {
                return newFixedLengthResponse(Response.Status.OK, MIME_JSON, "{}");
            } else {
                logStructured("WARN", "McPServer", correlationId, startTime,
                        "unknown method: " + method, null);
                response = McpJson.buildError(McpError.METHOD_NOT_FOUND, "Unknown: " + method, null, req.id);
            }

            if (metrics != null) {
                metrics.recordRequest();
                metrics.recordLatencyMs(elapsed(startTime));
            }
            requestCount.incrementAndGet();
            return newFixedLengthResponse(Response.Status.OK, MIME_JSON, response);
        } catch (McpError e) {
            logStructured("ERROR", "McPServer", correlationId, startTime,
                    "MCP error: " + e.getMessage(), e);
            logErrorToApi("mcp error: " + e.getMessage(), e, startTime);
            if (metrics != null) { metrics.recordError(); metrics.recordRequest(); }
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                    McpJson.buildError(e.getCode(), e.getMessage(), e.getData(), null));
        } catch (Exception e) {
            logStructured("ERROR", "McPServer", correlationId, startTime,
                    "internal error", e);
            logErrorToApi("internal error", e, startTime);
            if (metrics != null) { metrics.recordError(); metrics.recordRequest(); }
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_JSON,
                    McpJson.buildError(McpError.INTERNAL_ERROR, e.getMessage(), null, null));
        }
    }

    private Response handleHealth(IHTTPSession session, long startTime, String correlationId) {
        String format = session.getParms().getOrDefault("format", "json");
        try {
            // Verify Montoya API is accessible
            api.burpSuite().version().name();
        } catch (Exception e) {
            logStructured("ERROR", "McPServer:health", correlationId, startTime,
                    "API unreachable", e);
            return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_JSON,
                    "{\"status\":\"error\",\"message\":\"Burp API unreachable\"}");
        }

        if ("prometheus".equalsIgnoreCase(format) && metrics != null) {
            return newFixedLengthResponse(Response.Status.OK, "text/plain",
                    metrics.toPrometheusFormat());
        }

        long uptime = metrics != null ? metrics.getUptimeSeconds() : 0;
        String version = VersionInfo.getFullVersion();
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                "{\"status\":\"ok\",\"uptime\":" + uptime + ",\"version\":\"" + version + "\"}");
    }

    private void logStructured(String level, String source, String correlationId,
                                long startTime, String message, Throwable t) {
        if (!shouldLog(level)) return;
        long elapsed = elapsed(startTime);
        LogEntry entry = LogEntry.create(level, source, correlationId, elapsed, message, t);
        try { burp.mcp.util.ErrorLogger.log(entry); } catch (Exception ignored) {}
    }

    private boolean shouldLog(String level) {
        if (config == null) return true;
        String threshold = config.getLogLevel();
        return compareLevel(level, threshold) >= 0;
    }

    private static int compareLevel(String a, String b) {
        // Higher ordinal = more severe. We log if level >= threshold.
        return levelOrdinal(a) - levelOrdinal(b);
    }

    private static int levelOrdinal(String level) {
        return switch (level.toUpperCase()) {
            case "DEBUG" -> 0;
            case "INFO" -> 1;
            case "WARN" -> 2;
            case "ERROR" -> 3;
            default -> 1;
        };
    }

    private void notifyLogListener(String entry, String level) {
        if (!shouldLog(level)) return;
        Consumer<String> listener = logListener;
        if (listener != null) {
            try { listener.accept(entry); } catch (Exception ignored) {}
        }
    }

    private String summarizeParams(Map<String, Object> args) {
        if (args.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        for (var e : args.entrySet()) {
            if (sb.length() > 90) { sb.append("..."); break; }
            if (sb.length() > 1) sb.append(", ");
            sb.append(e.getKey()).append("=");
            Object v = e.getValue();
            String vs = v instanceof String s ? (s.length() > 30 ? s.substring(0, 30) + "..." : s) : String.valueOf(v);
            sb.append(vs);
        }
        sb.append("}");
        return sb.toString();
    }

    private Response badReq(String msg) {
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                McpJson.buildError(McpError.INVALID_REQUEST, msg, null, null));
    }

    private void logErrorToApi(String detail, Exception e, long startTime) {
        long elapsed = elapsed(startTime);
        String entry = String.format("[burp-mcp] ERROR | %s | %dms", detail, elapsed);
        try { api.logging().logToError(entry, e); } catch (Exception ignored) {}
        try { api.logging().logToOutput(entry + " - " + e.toString()); } catch (Exception ignored) {}
        try { burp.mcp.util.ErrorLogger.log(LogEntry.create("ERROR", "McPServer", null, elapsed, detail, e)); } catch (Exception ignored) {}
    }

    long elapsed(long startTime) {
        return System.currentTimeMillis() - startTime;
    }

    public boolean isRunning() { return isAlive(); }
    public long getRequestCount() { return requestCount.get(); }
    public PermissionManager getPermissions() { return permissions; }
    public MetricsCollector getMetrics() { return metrics; }
    public ConcurrentHashMap<String, CircuitBreaker> getCircuitBreakers() { return circuitBreakers; }
    public void enableTls(SSLServerSocketFactory f, String... p) { makeSecure(f, p); }
    public void setLogListener(Consumer<String> l) { this.logListener = l; }

    // ── Circuit breaker helpers ──

    private String checkCircuitBreaker(String toolName, String correlationId, long startTime) {
        if (!isExternalTool(toolName)) return null;
        CircuitBreaker cb = circuitBreakers.computeIfAbsent(toolName, CircuitBreaker::new);
        String deny = cb.allowRequest();
        if (deny != null) {
            logStructured("WARN", "McPServer:circuit", correlationId, startTime,
                    "CIRCUIT OPEN: " + toolName + " — " + deny, null);
        }
        return deny;
    }

    private void recordCircuitBreakerSuccess(String toolName) {
        CircuitBreaker cb = circuitBreakers.get(toolName);
        if (cb != null) cb.recordSuccess();
    }

    private void recordCircuitBreakerFailure(String toolName) {
        if (!isExternalTool(toolName)) return;
        CircuitBreaker cb = circuitBreakers.computeIfAbsent(toolName, CircuitBreaker::new);
        cb.recordFailure();
    }

    private boolean isExternalTool(String name) {
        return name.startsWith("http_send") || name.startsWith("scanner_start")
                || name.startsWith("collaborator_");
    }

    // ── Misc helpers ──

    private String extractClientIp(IHTTPSession session) {
        Map<String, String> h = session.getHeaders();
        String xff = null;
        for (Map.Entry<String, String> e : h.entrySet()) {
            if (e.getKey().equalsIgnoreCase("x-forwarded-for")) { xff = e.getValue(); break; }
        }
        if (xff != null) return xff.split(",")[0].trim();
        return session.getRemoteIpAddress() != null ? session.getRemoteIpAddress() : "127.0.0.1";
    }

    private static String sanitizeMessage(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) return t.getClass().getSimpleName();
        // Truncate to first line, remove internal details
        int nl = msg.indexOf('\n');
        if (nl > 0) msg = msg.substring(0, nl);
        if (msg.length() > 150) msg = msg.substring(0, 150) + "...";
        return msg;
    }
}
