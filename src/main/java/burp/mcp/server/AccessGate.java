package burp.mcp.server;

import burp.api.montoya.MontoyaApi;
import burp.mcp.util.ApprovalManager;
import burp.mcp.util.ErrorLogger;
import burp.mcp.util.McpConfig;
import burp.mcp.util.McpError;
import burp.mcp.util.McpJson;
import burp.mcp.util.PermissionManager;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Evaluates permission policy and Burp target scope for a tool call and
 * resolves "prompt" outcomes through the operator UI via {@link ApprovalManager}.
 *
 * <p>Decision protocol: gated calls wait up to the configured approval window
 * for an operator decision. If the operator answers in time the call is
 * allowed or denied immediately; otherwise the caller receives an
 * {@code APPROVAL_PENDING} result and can retry the identical call while the
 * request stays queued for its TTL.
 */
public class AccessGate {

    /** Config seams so tests can pin enforcement without the McpConfig singleton. */
    public interface Settings {
        String scopeEnforcement();
        int approvalWaitSeconds();
        int approvalTtlSeconds();
    }

    public enum Kind {
        ALLOW,
        DENY,
        PENDING
    }

    /** Outcome of a gate evaluation. */
    public static final class Result {
        private final Kind kind;
        private final int code;
        private final String message;
        private final Map<String, Object> data;

        private Result(Kind kind, int code, String message, Map<String, Object> data) {
            this.kind = kind;
            this.code = code;
            this.message = message;
            this.data = data;
        }

        static Result allow() {
            return new Result(Kind.ALLOW, 0, null, null);
        }

        static Result deny(int code, String message, Map<String, Object> data) {
            return new Result(Kind.DENY, code, message, data);
        }

        static Result pending(String message, Map<String, Object> data) {
            return new Result(Kind.PENDING, McpError.APPROVAL_PENDING, message, data);
        }

        public Kind getKind() { return kind; }
        public int getCode() { return code; }
        public String getMessage() { return message; }
        public Map<String, Object> getData() { return data; }

        public boolean isAllowed() { return kind == Kind.ALLOW; }
        public boolean isDenied() { return kind == Kind.DENY; }
        public boolean isPending() { return kind == Kind.PENDING; }
    }

    private final MontoyaApi api;
    private final PermissionManager permissions;
    private final ApprovalManager approvals;
    private final Settings settings;

    public AccessGate(MontoyaApi api, PermissionManager permissions, ApprovalManager approvals) {
        this(api, permissions, approvals, defaultSettings());
    }

    public AccessGate(MontoyaApi api, PermissionManager permissions,
                      ApprovalManager approvals, Settings settings) {
        this.api = api;
        this.permissions = permissions;
        this.approvals = approvals;
        this.settings = settings != null ? settings : defaultSettings();
        approvals.setScopeAdder(this::addToScope);
    }

    /**
     * Evaluate a tool call.
     *
     * @param clientIp  caller identity so retries match the same pending request
     * @param toolName  canonical tool name
     * @param args      tool arguments (used only for the fingerprint/summary)
     * @param targets   target URLs derived from {@link burp.mcp.tool.TargetedTool}
     */
    public Result check(String clientIp, String toolName,
                        Map<String, Object> args, List<String> targets) {
        String fingerprint = fingerprint(toolName, args);

        // Retry path: an unconsumed pending request (possibly already decided)
        // takes priority so a cached decision is honoured without re-prompting.
        ApprovalManager.PendingApproval existing = approvals.find(clientIp, fingerprint);
        if (existing != null) {
            return waitOn(existing);
        }

        PermissionManager.Access access = permissions.evaluate(toolName);
        if (access.isDenied()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("decision", "denied");
            data.put("source", "policy");
            data.put("tool", toolName);
            data.put("reason", access.getReason());
            return Result.deny(McpError.PERMISSION_DENIED, access.getReason(), data);
        }
        boolean permissionPrompt = access.isPrompt() && !approvals.isToolGranted(toolName);

        List<String> targetList = targets != null ? targets : List.of();
        List<String> outOfScope = new ArrayList<>();
        List<String> outOfScopeOrigins = new ArrayList<>();
        String enforcement = settings.scopeEnforcement();
        if (!"off".equals(enforcement) && !targetList.isEmpty()) {
            for (String target : targetList) {
                if (target == null || target.isBlank()) {
                    continue;
                }
                String origin = originOf(target);
                if (origin != null && approvals.isOriginGranted(origin)) {
                    continue;
                }
                if (!isInScope(target)) {
                    outOfScope.add(target);
                    if (origin != null && !outOfScopeOrigins.contains(origin)) {
                        outOfScopeOrigins.add(origin);
                    }
                }
            }
            if (!outOfScope.isEmpty() && "deny".equals(enforcement)) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("decision", "denied");
                data.put("source", "scope_policy");
                data.put("out_of_scope", true);
                data.put("tool", toolName);
                data.put("targets", outOfScope);
                return Result.deny(McpError.OUT_OF_SCOPE,
                        "Target not in Burp's target scope: " + String.join(", ", outOfScope)
                                + ". Add it with scope_set, or ask the operator to allow it.",
                        data);
            }
        }
        boolean scopePrompt = !outOfScope.isEmpty();

        if (!permissionPrompt && !scopePrompt) {
            return Result.allow();
        }

        String permissionReason = permissionPrompt ? access.getReason() : null;
        String scopeReason = scopePrompt
                ? "Target not in Burp's target scope: " + String.join(", ", outOfScope)
                : null;
        ApprovalManager.Context ctx = new ApprovalManager.Context(
                clientIp, toolName, fingerprint, summarize(args),
                targetList, outOfScope, outOfScopeOrigins,
                permissionPrompt, scopePrompt, permissionReason, scopeReason);
        ApprovalManager.PendingApproval pending =
                approvals.findOrCreate(ctx, settings.approvalTtlSeconds() * 1000L);
        return waitOn(pending);
    }

    private Result waitOn(ApprovalManager.PendingApproval pending) {
        long waitMs = settings.approvalWaitSeconds() * 1000L;
        ApprovalManager.Outcome outcome = approvals.await(pending, waitMs);
        switch (outcome.getKind()) {
            case DECIDED: {
                ApprovalManager.Decision decision = pending.getDecision();
                if (decision != null && decision.isDenied()) {
                    String reason = decision.reasonOrDefault("Denied by operator");
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("decision", "denied");
                    data.put("source", "operator");
                    data.put("tool", pending.getToolName());
                    data.put("reason", reason);
                    data.put("approval_id", pending.getId());
                    data.put("permission_denied",
                            decision.getPermission() == ApprovalManager.PermissionDecision.DENY);
                    data.put("scope_denied",
                            decision.getScope() == ApprovalManager.ScopeDecision.DENY);
                    if (!pending.getTargets().isEmpty()) {
                        data.put("targets", pending.getTargets());
                    }
                    return Result.deny(McpError.PERMISSION_DENIED,
                            "Operator denied '" + pending.getToolName() + "': " + reason, data);
                }
                approvals.consume(pending);
                return Result.allow();
            }
            case PENDING: {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("status", "pending");
                data.put("approval_id", pending.getId());
                data.put("tool", pending.getToolName());
                if (!pending.getTargets().isEmpty()) {
                    data.put("targets", pending.getTargets());
                }
                if (!pending.getOutOfScopeTargets().isEmpty()) {
                    data.put("out_of_scope", pending.getOutOfScopeTargets());
                }
                data.put("retry_after_ms", 5000);
                return Result.pending(
                        "Operator approval required for '" + pending.getToolName()
                                + "'. The request is pending (id " + pending.getId()
                                + "). Retry the same call with identical arguments in ~5s to "
                                + "collect the decision; it will be denied after "
                                + settings.approvalTtlSeconds() + "s without a decision.",
                        data);
            }
            case EXPIRED:
            default: {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("status", "expired");
                data.put("approval_id", pending.getId());
                data.put("tool", pending.getToolName());
                data.put("retryable", true);
                return Result.deny(McpError.APPROVAL_TIMEOUT,
                        "Approval request " + pending.getId() + " for '" + pending.getToolName()
                                + "' expired without a decision; the call was not executed. "
                                + "Retry to prompt the operator again.",
                        data);
            }
        }
    }

    /** Cancel every queued approval; called when the server stops. */
    public void onServerStop() {
        approvals.cancelAll("server stopped");
    }

    // ── Scope helpers ────────────────────────────────────────────────

    private boolean isInScope(String url) {
        try {
            var scope = api.scope();
            return scope == null || scope.isInScope(url);
        } catch (Exception e) {
            // A broken or mocked scope API must never hard-block calls.
            return true;
        }
    }

    private void addToScope(String origin) {
        try {
            api.scope().includeInScope(origin);
            try {
                api.logging().logToOutput("[burp-mcp] target scope updated by operator approval: " + origin);
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            ErrorLogger.log("AccessGate/addToScope", e);
        }
    }

    /** Lowercase scheme://host[:port], default ports omitted; null when unparsable. */
    public static String originOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return null;
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            boolean isSecure = "https".equals(scheme);
            int defaultPort = isSecure ? 443 : 80;
            boolean defaultPortUsed = port == -1 || port == defaultPort;
            String bracketed = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
            return scheme + "://" + bracketed + (defaultPortUsed ? "" : ":" + port);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    // ── Fingerprint / summary ────────────────────────────────────────

    static String fingerprint(String toolName, Map<String, Object> args) {
        String canonical = McpJson.toJson(normalize(args != null ? args : Map.of()));
        return sha256(toolName + "|" + canonical);
    }

    static String summarize(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        String json = McpJson.toJson(normalize(args));
        return json.length() > 400 ? json.substring(0, 400) + "..." : json;
    }

    /** Recursively sort map keys so equivalent argument objects share a fingerprint. */
    static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), normalize(e.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(normalize(item));
            }
            return out;
        }
        return value;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    static Settings defaultSettings() {
        return new Settings() {
            @Override
            public String scopeEnforcement() {
                McpConfig cfg = McpConfig.getInstance();
                return cfg != null ? cfg.getScopeEnforcement() : "prompt";
            }

            @Override
            public int approvalWaitSeconds() {
                McpConfig cfg = McpConfig.getInstance();
                return cfg != null ? cfg.getApprovalWaitSeconds() : 30;
            }

            @Override
            public int approvalTtlSeconds() {
                McpConfig cfg = McpConfig.getInstance();
                return cfg != null ? cfg.getApprovalTtlSeconds() : 300;
            }
        };
    }
}
