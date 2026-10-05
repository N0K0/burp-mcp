package burp.mcp.tool;

import java.util.List;
import java.util.Map;

/**
 * Implemented by tools that act on a target host so the access gate can
 * evaluate Burp's target scope before the tool executes.
 *
 * <p>{@link #targetUrls} must derive the targets from the raw arguments
 * without side effects. It returns an empty list when the targets cannot be
 * determined (for example malformed arguments); argument validation errors
 * are left to the tool itself so the agent gets the tool's own diagnostics.
 */
public interface TargetedTool {

    /** Target URLs this call would send traffic to; never null. */
    List<String> targetUrls(Map<String, Object> args);
}
