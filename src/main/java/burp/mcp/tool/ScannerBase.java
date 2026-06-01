package burp.mcp.tool;

import burp.api.montoya.core.BurpSuiteEdition;
import burp.api.montoya.MontoyaApi;
import burp.mcp.util.McpError;

/**
 * Base class for Scanner tools that checks for Burp Suite Professional edition.
 * All scanner features require Pro edition, so we guard at the base class level.
 */
public abstract class ScannerBase {

    protected final MontoyaApi api;

    protected ScannerBase(MontoyaApi api) {
        this.api = api;
    }

    /**
     * Check that Burp Suite Professional is running.
     * Throws McpError if Community edition is detected.
     */
    protected void checkProEdition() {
        if (api.burpSuite().version().edition() != BurpSuiteEdition.PROFESSIONAL) {
            throw new McpError(McpError.PRO_ONLY_FEATURE,
                    "This tool requires Burp Suite Professional edition");
        }
    }
}
