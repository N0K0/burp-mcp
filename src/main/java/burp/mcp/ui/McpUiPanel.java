package burp.mcp.ui;

import burp.api.montoya.MontoyaApi;
import burp.mcp.server.McpServerManager;
import burp.mcp.tool.McpToolRegistry;
import burp.mcp.util.PermissionManager;

import javax.swing.*;
import java.awt.*;

/**
 * Main UI tab for the Burp MCP Server extension.
 * All sub-panels are wrapped in SafePanel to catch Swing rendering NPEs.
 */
public class McpUiPanel extends JPanel {

    private final StatusPanel statusPanel;
    private final SettingsPanel settingsPanel;
    private final ToolTesterPanel toolTesterPanel;
    private final PermissionsPanel permissionsPanel;
    private final MessageViewerPanel messageViewerPanel;

    public McpUiPanel(MontoyaApi api, McpToolRegistry registry, PermissionManager permissions,
                      McpServerManager serverManager) {
        setLayout(new BorderLayout());

        // Create panels (each wrapped for safety)
        statusPanel = new StatusPanel();
        settingsPanel = new SettingsPanel();
        toolTesterPanel = new ToolTesterPanel(api, registry);
        permissionsPanel = new PermissionsPanel(api, permissions, registry);
        messageViewerPanel = new MessageViewerPanel(api);

        // Tabbed pane with safe wrappers
        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
        tabs.addTab("Status", new SafePanel("StatusPanel", () -> statusPanel));
        tabs.addTab("Settings", new SafePanel("SettingsPanel", () -> settingsPanel));
        tabs.addTab("Tool Tester", new SafePanel("ToolTesterPanel", () -> toolTesterPanel));
        tabs.addTab("Permissions", new SafePanel("PermissionsPanel", () -> permissionsPanel));
        tabs.addTab("Messages", new SafePanel("MessageViewerPanel", () -> messageViewerPanel));

        add(tabs, BorderLayout.CENTER);

        // Footer
        JLabel footer = new JLabel(" Burp MCP Server — Montoya API — JSON-RPC 2.0 over HTTP ");
        footer.setFont(footer.getFont().deriveFont(Font.ITALIC, 10));
        footer.setForeground(Color.GRAY);
        footer.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
        add(footer, BorderLayout.SOUTH);

        statusPanel.setManager(serverManager);
    }

    public StatusPanel getStatusPanel() { return statusPanel; }
    public SettingsPanel getSettingsPanel() { return settingsPanel; }
    public ToolTesterPanel getToolTesterPanel() { return toolTesterPanel; }
    public PermissionsPanel getPermissionsPanel() { return permissionsPanel; }
    public MessageViewerPanel getMessageViewerPanel() { return messageViewerPanel; }
}
