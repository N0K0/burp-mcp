package burp.mcp.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Middleware permission manager for MCP tool access control.
 *
 * Four operating modes:
 *   READ_ONLY  — only tools that don't modify state (query, list, parse, decode)
 *   READ_WRITE — all tools allowed (default)
 *   PROMPT     — read tools allowed, write tools require operator approval
 *   CUSTOM     — per-tool policy: ALLOW, PROMPT or DENY
 *
 * Sensitivity gate: when blockSensitive is true, tools tagged as sensitive
 * are denied regardless of mode (a hard deny that never prompts).
 */
public class PermissionManager {

    public enum Level {
        READ_ONLY,
        READ_WRITE,
        PROMPT,
        CUSTOM
    }

    /** Per-tool policy for CUSTOM mode. */
    public enum Policy {
        ALLOW,
        PROMPT,
        DENY
    }

    /** Evaluation result for one tool call. */
    public static final class Access {
        private final Policy policy;
        private final String reason;

        private Access(Policy policy, String reason) {
            this.policy = policy;
            this.reason = reason;
        }

        public Policy getPolicy() {
            return policy;
        }

        /** Human-readable explanation when the policy is PROMPT or DENY. */
        public String getReason() {
            return reason;
        }

        public boolean isAllowed() {
            return policy == Policy.ALLOW;
        }

        public boolean isPrompt() {
            return policy == Policy.PROMPT;
        }

        public boolean isDenied() {
            return policy == Policy.DENY;
        }
    }

    // ── State ────────────────────────────────────────────────────────
    private volatile Level level = Level.READ_WRITE;
    private volatile boolean blockSensitive = false;
    private final Map<String, Policy> toolPolicies = new ConcurrentHashMap<>();

    // ── Tool categories ──────────────────────────────────────────────

    /** Tools that only read/query — safe for READ_ONLY mode. */
    private static final Set<String> READ_TOOLS = Set.of(
        "burp_info",
        "project_create",
        "burp_metrics",
        "scanner_crawl_status",
        "sitemap_list", "sitemap_list_filtered", "sitemap_get", "sitemap_search",
        "proxy_history_list", "proxy_history_get", "proxy_websocket_history_list",
        "websocket_history_get",
        "proxy_intercept_status",
        "scope_check", "scope_list",
        "organizer_list",
        "task_engine_status",
        "cookie_list",
        "decoder_decode", "decoder_encode",
        "http_parse_request", "http_parse_response",
        "http_get_request",
        "config_get", "config_list_preferences",
        "scanner_issues_list", "scanner_issues_list_filtered", "scanner_get_issue",
        "collaborator_interactions",
        "http_diff_responses", "http_keyword_search"
    );

    /** Tools that require higher caution — blocked when sensitivity is on. */
    private static final Set<String> SENSITIVE_TOOLS = Set.of(
        "scope_set",           // modifies target scope
        "config_set",          // imports configuration
        "scanner_start_audit", // launches active scans
        "scanner_start_crawl", // launches crawls
        "scanner_crawl_stop", // stops crawls
        "scanner_bcheck_import", // installs custom scan checks
        "task_engine_set", // pauses/resumes all Burp tasks
        "proxy_toggle_intercept" // toggles proxy state
    );

    // ── Public API ───────────────────────────────────────────────────

    public Level getLevel() {
        return level;
    }

    public void setLevel(Level level) {
        this.level = level;
    }

    public boolean isBlockSensitive() {
        return blockSensitive;
    }

    public void setBlockSensitive(boolean block) {
        this.blockSensitive = block;
    }

    // ── Per-tool policies (CUSTOM mode) ──────────────────────────────

    /** Set the explicit policy for a tool in CUSTOM mode. */
    public void setToolPolicy(String toolName, Policy policy) {
        if (toolName == null || policy == null) {
            return;
        }
        toolPolicies.put(toolName, policy);
    }

    /** Explicit policy for a tool in CUSTOM mode; unset defaults to ALLOW. */
    public Policy getToolPolicy(String toolName) {
        return toolPolicies.getOrDefault(toolName, Policy.ALLOW);
    }

    /** Replace the full per-tool policy map (for bulk import / Apply). */
    public void setToolPolicies(Map<String, Policy> policies) {
        toolPolicies.clear();
        if (policies != null) {
            toolPolicies.putAll(policies);
        }
    }

    /** Snapshot of the explicitly configured per-tool policies. */
    public Map<String, Policy> getToolPolicies() {
        return Collections.unmodifiableMap(new HashMap<>(toolPolicies));
    }

    /** Enable a tool in CUSTOM mode (policy ALLOW). */
    public void enableTool(String toolName) {
        setToolPolicy(toolName, Policy.ALLOW);
    }

    /** Disable a tool in CUSTOM mode (policy DENY). */
    public void disableTool(String toolName) {
        setToolPolicy(toolName, Policy.DENY);
    }

    /** Check if a tool is not explicitly denied in CUSTOM mode. */
    public boolean isToolEnabled(String toolName) {
        return getToolPolicy(toolName) != Policy.DENY;
    }

    /** Set the full set of allowed tools (others keep their default ALLOW). */
    public void setEnabledTools(Set<String> tools) {
        setToolPolicies(new HashMap<>());
        if (tools != null) {
            for (String t : tools) {
                toolPolicies.put(t, Policy.ALLOW);
            }
        }
    }

    /** Set the full set of denied tools. */
    public void setDisabledTools(Set<String> tools) {
        toolPolicies.clear();
        if (tools != null) {
            for (String t : tools) {
                toolPolicies.put(t, Policy.DENY);
            }
        }
    }

    public Set<String> getEnabledTools() {
        Set<String> out = new HashSet<>();
        for (var e : toolPolicies.entrySet()) {
            if (e.getValue() == Policy.ALLOW) {
                out.add(e.getKey());
            }
        }
        return Collections.unmodifiableSet(out);
    }

    public Set<String> getDisabledTools() {
        Set<String> out = new HashSet<>();
        for (var e : toolPolicies.entrySet()) {
            if (e.getValue() == Policy.DENY) {
                out.add(e.getKey());
            }
        }
        return Collections.unmodifiableSet(out);
    }

    public Set<String> getPromptTools() {
        Set<String> out = new HashSet<>();
        for (var e : toolPolicies.entrySet()) {
            if (e.getValue() == Policy.PROMPT) {
                out.add(e.getKey());
            }
        }
        return Collections.unmodifiableSet(out);
    }

    // ── Access check ─────────────────────────────────────────────────

    /**
     * Evaluate the policy for a tool call.
     * The returned {@link Access} is never null: it is ALLOW, PROMPT or DENY.
     */
    public Access evaluate(String toolName) {
        Policy policy;
        String reason = null;

        switch (level) {
            case READ_ONLY:
                if (isReadTool(toolName)) {
                    policy = Policy.ALLOW;
                } else {
                    policy = Policy.DENY;
                    reason = "Tool '" + toolName + "' is a write/modify operation and the permission "
                           + "level is READ_ONLY. Change the level to READ_WRITE or PROMPT in the "
                           + "Burp MCP Permissions tab.";
                }
                break;
            case READ_WRITE:
                policy = Policy.ALLOW;
                break;
            case PROMPT:
                if (isReadTool(toolName)) {
                    policy = Policy.ALLOW;
                } else {
                    policy = Policy.PROMPT;
                    reason = "Tool '" + toolName + "' is a write/modify operation and the permission "
                           + "mode is PROMPT: operator approval is required.";
                }
                break;
            case CUSTOM:
            default:
                policy = getToolPolicy(toolName);
                if (policy == Policy.DENY) {
                    reason = "Tool '" + toolName + "' is disabled in CUSTOM permission mode.";
                } else if (policy == Policy.PROMPT) {
                    reason = "Tool '" + toolName + "' requires operator approval under the current "
                           + "CUSTOM permission policy.";
                }
                break;
        }

        // Sensitivity gate: hard deny that takes precedence in every mode.
        if (blockSensitive && SENSITIVE_TOOLS.contains(toolName)) {
            return new Access(Policy.DENY,
                    "Tool '" + toolName + "' is marked as sensitive and sensitivity blocking is "
                  + "enabled. Disable sensitivity blocking in the Permissions tab to use this tool.");
        }

        return new Access(policy, reason);
    }

    // ── Static helpers for UI ────────────────────────────────────────

    public static boolean isReadTool(String toolName) {
        return READ_TOOLS.contains(toolName);
    }

    public static boolean isSensitive(String toolName) {
        return SENSITIVE_TOOLS.contains(toolName);
    }

    public static Set<String> getReadTools() {
        return READ_TOOLS;
    }

    public static Set<String> getSensitiveTools() {
        return SENSITIVE_TOOLS;
    }
}
