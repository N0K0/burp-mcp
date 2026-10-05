package burp.mcp.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionManagerTest {

    private PermissionManager pm;

    @BeforeEach
    void setUp() {
        pm = new PermissionManager();
    }

    @Test
    void defaultLevel_shouldBeReadWrite() {
        assertThat(pm.getLevel()).isEqualTo(PermissionManager.Level.READ_WRITE);
    }

    @Test
    void readWriteMode_shouldAllowAllTools() {
        pm.setLevel(PermissionManager.Level.READ_WRITE);
        assertThat(pm.evaluate("burp_info").isAllowed()).isTrue();
        assertThat(pm.evaluate("scope_set").isAllowed()).isTrue();
        assertThat(pm.evaluate("http_send_request").isAllowed()).isTrue();
    }

    @Test
    void readOnlyMode_shouldAllowReadTools() {
        pm.setLevel(PermissionManager.Level.READ_ONLY);
        assertThat(pm.evaluate("burp_info").isAllowed()).isTrue();
        assertThat(pm.evaluate("proxy_history_list").isAllowed()).isTrue();
        assertThat(pm.evaluate("decoder_decode").isAllowed()).isTrue();
        assertThat(pm.evaluate("scope_list").isAllowed()).isTrue();
    }

    @Test
    void readOnlyMode_shouldDenyWriteTools() {
        pm.setLevel(PermissionManager.Level.READ_ONLY);
        for (String tool : new String[]{"scope_set", "http_send_request"}) {
            PermissionManager.Access access = pm.evaluate(tool);
            assertThat(access.isDenied()).as(tool).isTrue();
            assertThat(access.getReason()).as(tool).contains("READ_ONLY");
        }
    }

    @Test
    void promptMode_shouldAllowReadToolsButPromptWrites() {
        pm.setLevel(PermissionManager.Level.PROMPT);
        assertThat(pm.evaluate("burp_info").isAllowed()).isTrue();
        assertThat(pm.evaluate("sitemap_list").isAllowed()).isTrue();

        PermissionManager.Access write = pm.evaluate("http_send_request");
        assertThat(write.isPrompt()).isTrue();
        assertThat(write.getReason()).contains("PROMPT").contains("approval");

        PermissionManager.Access sensitiveWrite = pm.evaluate("scanner_start_audit");
        assertThat(sensitiveWrite.isPrompt()).isTrue();
    }

    @Test
    void customMode_shouldUsePerToolPolicy() {
        pm.setLevel(PermissionManager.Level.CUSTOM);
        // Unset tools default to ALLOW (preserves pre-PROMPT behaviour)
        assertThat(pm.evaluate("burp_info").isAllowed()).isTrue();
        assertThat(pm.evaluate("scope_set").isAllowed()).isTrue();

        pm.disableTool("scope_set");
        PermissionManager.Access denied = pm.evaluate("scope_set");
        assertThat(denied.isDenied()).isTrue();
        assertThat(denied.getReason()).contains("CUSTOM");

        pm.setToolPolicy("http_send_request", PermissionManager.Policy.PROMPT);
        PermissionManager.Access prompted = pm.evaluate("http_send_request");
        assertThat(prompted.isPrompt()).isTrue();
        assertThat(prompted.getReason()).contains("CUSTOM").contains("approval");

        pm.enableTool("scope_set");
        assertThat(pm.evaluate("scope_set").isAllowed()).isTrue();
    }

    @Test
    void policyMap_shouldRoundTrip() {
        pm.setToolPolicies(Map.of(
                "http_send_request", PermissionManager.Policy.PROMPT,
                "scope_set", PermissionManager.Policy.DENY));
        assertThat(pm.getToolPolicy("http_send_request")).isEqualTo(PermissionManager.Policy.PROMPT);
        assertThat(pm.getToolPolicy("scope_set")).isEqualTo(PermissionManager.Policy.DENY);
        assertThat(pm.getToolPolicy("burp_info")).isEqualTo(PermissionManager.Policy.ALLOW);
        assertThat(pm.getPromptTools()).containsExactly("http_send_request");
        assertThat(pm.getDisabledTools()).containsExactly("scope_set");
        assertThat(pm.isToolEnabled("scope_set")).isFalse();
        assertThat(pm.isToolEnabled("http_send_request")).isTrue();
    }

    @Test
    void sensitivityGate_shouldHardDenyInAllModes() {
        pm.setBlockSensitive(true);

        pm.setLevel(PermissionManager.Level.READ_WRITE);
        assertThat(pm.evaluate("scope_set").isDenied()).isTrue();
        assertThat(pm.evaluate("scope_set").getReason()).contains("sensitive");
        assertThat(pm.evaluate("config_set").isDenied()).isTrue();
        assertThat(pm.evaluate("scanner_start_audit").isDenied()).isTrue();

        // PROMPT mode must not downgrade a sensitivity block to a prompt
        pm.setLevel(PermissionManager.Level.PROMPT);
        assertThat(pm.evaluate("scanner_start_audit").isDenied()).isTrue();

        // CUSTOM with an explicit ALLOW is still overridden by the gate
        pm.setLevel(PermissionManager.Level.CUSTOM);
        pm.enableTool("scope_set");
        assertThat(pm.evaluate("scope_set").isDenied()).isTrue();

        // Normal tools unaffected
        assertThat(pm.evaluate("burp_info").isAllowed()).isTrue();
        assertThat(pm.evaluate("http_send_request").isAllowed()).isTrue();
    }

    @Test
    void sensitivityOff_shouldAllowSensitiveInReadWrite() {
        pm.setBlockSensitive(false);
        pm.setLevel(PermissionManager.Level.READ_WRITE);
        assertThat(pm.evaluate("scope_set").isAllowed()).isTrue();
    }

    @Test
    void readOnlyMode_shouldAllowNewQueryTools() {
        pm.setLevel(PermissionManager.Level.READ_ONLY);
        assertThat(pm.evaluate("scope_list").isAllowed()).isTrue();
        assertThat(pm.evaluate("sitemap_search").isAllowed()).isTrue();
        assertThat(pm.evaluate("config_list_preferences").isAllowed()).isTrue();
        assertThat(pm.evaluate("websocket_history_get").isAllowed()).isTrue();
        assertThat(pm.evaluate("burp_metrics").isAllowed()).isTrue();
        assertThat(pm.evaluate("organizer_list").isAllowed()).isTrue();
        assertThat(pm.evaluate("task_engine_status").isAllowed()).isTrue();
        assertThat(pm.evaluate("scanner_crawl_status").isAllowed()).isTrue();
    }

    @Test
    void toolCategories_shouldHaveExpectedReadTools() {
        assertThat(PermissionManager.isReadTool("burp_info")).isTrue();
        assertThat(PermissionManager.isReadTool("sitemap_list")).isTrue();
        assertThat(PermissionManager.isReadTool("http_send_request")).isFalse();
        assertThat(PermissionManager.isReadTool("scope_set")).isFalse();
        assertThat(PermissionManager.isReadTool("logger_add")).isFalse();
    }

    @Test
    void toolCategories_shouldHaveExpectedSensitiveTools() {
        assertThat(PermissionManager.isSensitive("scope_set")).isTrue();
        assertThat(PermissionManager.isSensitive("config_set")).isTrue();
        assertThat(PermissionManager.isSensitive("scanner_start_audit")).isTrue();
        assertThat(PermissionManager.isSensitive("scanner_start_crawl")).isTrue();
        assertThat(PermissionManager.isSensitive("scanner_bcheck_import")).isTrue();
        assertThat(PermissionManager.isSensitive("task_engine_set")).isTrue();
        assertThat(PermissionManager.isSensitive("proxy_toggle_intercept")).isTrue();

        assertThat(PermissionManager.isSensitive("burp_info")).isFalse();
        assertThat(PermissionManager.isSensitive("http_send_request")).isFalse();
    }
}
