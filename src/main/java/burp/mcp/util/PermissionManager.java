package burp.mcp.util;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Middleware permission manager for MCP tool access control.
 *
 * Three operating modes:
 *   READ_ONLY  — only tools that don't modify state (query, list, parse, decode)
 *   READ_WRITE — all tools allowed (default)
 *   CUSTOM     — per-tool enable/disable via the enabledTools set
 *
 * Sensitivity gate: when blockSensitive is true, tools tagged as sensitive
 * are denied regardless of mode. In CUSTOM mode an explicit allow still
 * does not bypass the sensitivity gate — disable sensitivity blocking
 * in the Permissions tab to use these tools.
 */
public class PermissionManager {

    public enum Level {
        READ_ONLY,
        READ_WRITE,
        CUSTOM
    }

    // ── State ────────────────────────────────────────────────────────
    private volatile Level level = Level.READ_WRITE;
    private volatile boolean blockSensitive = false;
    private final Set<String> enabledTools = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> disabledTools = Collections.synchronizedSet(new HashSet<>());

    // ── Tool categories ──────────────────────────────────────────────

    /** Tools that only read/query — safe for READ_ONLY mode. */
    private static final Set<String> READ_TOOLS = Set.of(
        "burp_info",
        "burp_metrics",
        "sitemap_list", "sitemap_list_filtered", "sitemap_get", "sitemap_search",
        "proxy_history_list", "proxy_history_get", "proxy_websocket_history_list",
        "websocket_history_get",
        "proxy_intercept_status",
        "scope_check", "scope_list",
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

    /** Enable a tool in CUSTOM mode. */
    public void enableTool(String toolName) {
        enabledTools.add(toolName);
        disabledTools.remove(toolName);
    }

    /** Disable a tool in CUSTOM mode. */
    public void disableTool(String toolName) {
        disabledTools.add(toolName);
        enabledTools.remove(toolName);
    }

    /** Check if a tool is explicitly enabled in CUSTOM mode. */
    public boolean isToolEnabled(String toolName) {
        if (disabledTools.contains(toolName)) return false;
        if (enabledTools.contains(toolName)) return true;
        // If not explicitly set, default to enabled
        return true;
    }

    /** Set the full set of enabled tools (for bulk import from preferences). */
    public void setEnabledTools(Set<String> tools) {
        enabledTools.clear();
        enabledTools.addAll(tools);
    }

    /** Set the full set of disabled tools. */
    public void setDisabledTools(Set<String> tools) {
        disabledTools.clear();
        disabledTools.addAll(tools);
    }

    public Set<String> getEnabledTools() {
        return Collections.unmodifiableSet(new HashSet<>(enabledTools));
    }

    public Set<String> getDisabledTools() {
        return Collections.unmodifiableSet(new HashSet<>(disabledTools));
    }

    // ── Access check ─────────────────────────────────────────────────

    /**
     * Check whether a tool call is permitted.
     * @return null if allowed, or an error message string if denied.
     */
    public String checkAccess(String toolName) {
        switch (level) {
            case READ_ONLY:
                if (!READ_TOOLS.contains(toolName)) {
                    return "Tool '" + toolName + "' is a write/modify operation. "
                         + "Permission level is READ_ONLY. Use the Burp MCP UI to change to READ_WRITE.";
                }
                break;
            case CUSTOM:
                if (!isToolEnabled(toolName)) {
                    return "Tool '" + toolName + "' is disabled in CUSTOM permission mode.";
                }
                break;
            case READ_WRITE:
                // All tools allowed at base level
                break;
        }

        // Sensitivity gate: if on, block sensitive tools (applies in all modes)
        if (blockSensitive && SENSITIVE_TOOLS.contains(toolName)) {
            return "Tool '" + toolName + "' is marked as sensitive and sensitivity blocking is enabled. "
                 + "Disable sensitivity blocking in the Permissions tab to use this tool.";
        }

        return null; // allowed
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
