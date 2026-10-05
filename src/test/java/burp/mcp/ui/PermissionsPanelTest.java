package burp.mcp.ui;

import burp.mcp.util.PermissionManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure UI logic that does not need a Swing context. Panel construction needs
 * a live Montoya API, so this covers the policy cycle that CUSTOM mode uses.
 */
class PermissionsPanelTest {

    @Test
    void nextPolicy_shouldCycleAllowPromptDeny() {
        PermissionManager.Policy policy = PermissionManager.Policy.ALLOW;
        policy = PermissionsPanel.nextPolicy(policy);
        assertThat(policy).isEqualTo(PermissionManager.Policy.PROMPT);
        policy = PermissionsPanel.nextPolicy(policy);
        assertThat(policy).isEqualTo(PermissionManager.Policy.DENY);
        policy = PermissionsPanel.nextPolicy(policy);
        assertThat(policy).isEqualTo(PermissionManager.Policy.ALLOW);
    }
}
