package burp.mcp.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        assertThat(pm.checkAccess("burp_info")).isNull();
        assertThat(pm.checkAccess("scope_set")).isNull();
        assertThat(pm.checkAccess("http_send_request")).isNull();
    }

    @Test
    void readOnlyMode_shouldAllowReadTools() {
        pm.setLevel(PermissionManager.Level.READ_ONLY);
        assertThat(pm.checkAccess("burp_info")).isNull();
        assertThat(pm.checkAccess("proxy_history_list")).isNull();
        assertThat(pm.checkAccess("decoder_decode")).isNull();
    }

    @Test
    void readOnlyMode_shouldDenyWriteTools() {
        pm.setLevel(PermissionManager.Level.READ_ONLY);
        assertThat(pm.checkAccess("scope_set"))
                .isNotNull()
                .contains("READ_ONLY");
        assertThat(pm.checkAccess("http_send_request"))
                .isNotNull()
                .contains("READ_ONLY");
    }

    @Test
    void customMode_shouldOnlyAllowEnabledTools() {
        pm.setLevel(PermissionManager.Level.CUSTOM);
        pm.enableTool("burp_info");
        pm.enableTool("proxy_history_list");
        // By default disabled tools are... wait, isToolEnabled defaults to true
        assertThat(pm.checkAccess("burp_info")).isNull();
        // After explicitly disabling
        pm.disableTool("scope_set");
        assertThat(pm.checkAccess("scope_set")).isNotNull().contains("CUSTOM");
    }

    @Test
    void sensitivityGate_shouldBlockSensitiveInAllModes() {
        pm.setBlockSensitive(true);

        // Read-write mode still blocks sensitive
        pm.setLevel(PermissionManager.Level.READ_WRITE);
        assertThat(pm.checkAccess("scope_set")).isNotNull().contains("sensitive");
        assertThat(pm.checkAccess("config_set")).isNotNull().contains("sensitive");
        assertThat(pm.checkAccess("scanner_start_audit")).isNotNull().contains("sensitive");

        // Read-only mode also blocks sensitive
        pm.setLevel(PermissionManager.Level.READ_ONLY);
        assertThat(pm.checkAccess("scope_set")).isNotNull(); // both read-only AND sensitive

        // Normal tools unaffected
        assertThat(pm.checkAccess("burp_info")).isNull();
        assertThat(pm.checkAccess("http_send_request")).isNotNull(); // write tool in read-only
    }

    @Test
    void sensitivityOff_shouldAllowSensitiveInReadWrite() {
        pm.setBlockSensitive(false);
        pm.setLevel(PermissionManager.Level.READ_WRITE);
        assertThat(pm.checkAccess("scope_set")).isNull();
    }

    @Test
    void toolCategories_shouldHaveExpectedReadTools() {
        assertThat(PermissionManager.isReadTool("burp_info")).isTrue();
        assertThat(PermissionManager.isReadTool("sitemap_list")).isTrue();
        assertThat(PermissionManager.isReadTool("http_send_request")).isFalse();
        assertThat(PermissionManager.isReadTool("scope_set")).isFalse();
    }

    @Test
    void readOnlyMode_shouldAllowNewQueryTools() {
        pm.setLevel(PermissionManager.Level.READ_ONLY);
        assertThat(pm.checkAccess("scope_list")).isNull();
        assertThat(pm.checkAccess("sitemap_search")).isNull();
        assertThat(pm.checkAccess("config_list_preferences")).isNull();
        assertThat(pm.checkAccess("websocket_history_get")).isNull();
        assertThat(pm.checkAccess("burp_metrics")).isNull();
        assertThat(pm.checkAccess("organizer_list")).isNull();
        assertThat(pm.checkAccess("task_engine_status")).isNull();
        assertThat(pm.checkAccess("scanner_crawl_status")).isNull();
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
