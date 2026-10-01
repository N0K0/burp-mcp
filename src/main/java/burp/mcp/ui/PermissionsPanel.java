package burp.mcp.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.BurpSuiteEdition;
import burp.mcp.tool.McpToolRegistry;
import burp.mcp.util.PermissionManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;

/**
 * Permission control with a collapsible tree — categories as parent nodes,
 * tools as checkable leaf nodes with color-coded icons.
 */
public class PermissionsPanel extends JPanel {

    private final PermissionManager permissions;
    private final McpToolRegistry registry;
    private final boolean isProfessional;

    private final JComboBox<String> presetCombo;
    private final JCheckBox sensitivityCheck;
    private final JTree tree;
    private final DefaultMutableTreeNode rootNode;
    private final JTextField searchField;
    private final JLabel statusLabel;
    private final JLabel summaryLabel;

    private final java.util.List<DefaultMutableTreeNode> categoryNodes = new ArrayList<>();
    // tool name → checkbox node
    private final Map<String, DefaultMutableTreeNode> toolNodes = new LinkedHashMap<>();
    private final java.util.List<String> allToolNames = new ArrayList<>();

    private boolean updatingFromPreset = false;

    private static final LinkedHashMap<String, String[]> CATEGORIES = new LinkedHashMap<>();
    static {
        CATEGORIES.put("Info & Metrics", new String[]{"burp_info", "burp_metrics", "config_list_preferences"});
        CATEGORIES.put("HTTP Request/Response", new String[]{"http_", "logger_add"});
        CATEGORIES.put("Proxy & WebSocket", new String[]{"proxy_", "websocket_"});
        CATEGORIES.put("Sitemap & Scope", new String[]{"sitemap_", "scope_"});
        CATEGORIES.put("Decoder, Cookies & Analysis", new String[]{"decoder_", "cookie_", "http_diff", "http_keyword", "http_send_to_"});
        CATEGORIES.put("Config", new String[]{"config_get", "config_set"});
        CATEGORIES.put("Scanner", new String[]{"scanner_"});
        CATEGORIES.put("Collaborator", new String[]{"collaborator_"});
    }

    public PermissionsPanel(MontoyaApi api, PermissionManager permissions, McpToolRegistry registry) {
        this.permissions = permissions;
        this.registry = registry;
        this.isProfessional = api.burpSuite().version().edition() == BurpSuiteEdition.PROFESSIONAL;

        setLayout(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(10, 10, 10, 10));

        // ── Top: presets + sensitivity ──
        JPanel topPanel = new JPanel(new GridBagLayout());
        topPanel.setBorder(new TitledBorder("Access Control"));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 8, 4, 8);
        g.anchor = GridBagConstraints.WEST;

        g.gridx = 0; g.gridy = 0; g.weightx = 0;
        topPanel.add(new JLabel("Mode:"), g);
        g.gridx = 1; g.weightx = 1.0; g.fill = GridBagConstraints.HORIZONTAL;
        presetCombo = new JComboBox<>(new String[]{"Read-Write (all tools)", "Read-Only (query/list/decode)", "Custom"});
        presetCombo.setSelectedIndex(indexForLevel(permissions.getLevel()));
        presetCombo.addActionListener(e -> applyPreset());
        topPanel.add(presetCombo, g);

        g.gridwidth = 2; g.gridx = 0; g.gridy = 1;
        sensitivityCheck = new JCheckBox("Block sensitive ops (scope_set, config_set, scanner_*, proxy_toggle_intercept)");
        sensitivityCheck.setSelected(permissions.isBlockSensitive());
        sensitivityCheck.addActionListener(e -> { applyPreset(); });
        topPanel.add(sensitivityCheck, g);

        summaryLabel = new JLabel();
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.ITALIC, 11));
        g.gridy = 2;
        topPanel.add(summaryLabel, g);

        // ── Quick buttons + search ──
        JPanel quickPanel = new JPanel(new BorderLayout(5, 0));
        quickPanel.setBorder(new EmptyBorder(5, 8, 5, 8));
        JPanel quickBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        JButton allRead = new JButton("All Read");
        allRead.addActionListener(e -> setAllRead(true));
        JButton allWrite = new JButton("All Write");
        allWrite.addActionListener(e -> setAllRead(false));
        JButton selectAll = new JButton("Select All");
        selectAll.addActionListener(e -> checkAll(true));
        JButton deselectAll = new JButton("None");
        deselectAll.addActionListener(e -> checkAll(false));
        quickBtns.add(allRead); quickBtns.add(allWrite);
        quickBtns.add(selectAll); quickBtns.add(deselectAll);
        quickPanel.add(quickBtns, BorderLayout.WEST);

        searchField = new JTextField(15);
        searchField.setToolTipText("Filter tools by name");
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void filter() { applyFilter(); }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { filter(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { filter(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { filter(); }
        });
        JPanel searchPnl = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        searchPnl.add(new JLabel("Filter:"));
        searchPnl.add(searchField);
        quickPanel.add(searchPnl, BorderLayout.EAST);

        // ── Tree ──
        rootNode = new DefaultMutableTreeNode("Tools");
        buildTree();

        DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
        tree = new JTree(treeModel);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new ToolTreeRenderer());
        tree.setRowHeight(22);
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (updatingFromPreset) return;
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path == null) return;
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                if (node.getUserObject() instanceof ToolInfo ti) {
                    ti.selected = !ti.selected;
                    treeModel.nodeChanged(node);
                }
            }
        });

        // Expand all categories by default
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }

        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

        // ── Bottom: Apply ──
        JPanel bottom = new JPanel(new BorderLayout());
        JPanel btnPnl = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        JButton applyBtn = new JButton("Apply");
        applyBtn.addActionListener(e -> applyPermissions());
        statusLabel = new JLabel("");
        btnPnl.add(applyBtn);
        btnPnl.add(statusLabel);
        bottom.add(btnPnl, BorderLayout.NORTH);

        add(topPanel, BorderLayout.NORTH);
        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.add(quickPanel, BorderLayout.NORTH);
        centerPanel.add(treeScroll, BorderLayout.CENTER);
        add(centerPanel, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        updateSummary();
        applyPreset();
    }

    private void buildTree() {
        rootNode.removeAllChildren();
        categoryNodes.clear();
        toolNodes.clear();
        allToolNames.clear();

        Set<String> allNames = new TreeSet<>(registry.getToolNames());
        Set<String> matched = new HashSet<>();

        for (var entry : CATEGORIES.entrySet()) {
            DefaultMutableTreeNode catNode = new DefaultMutableTreeNode(entry.getKey());
            boolean hasTools = false;
            for (String name : allNames) {
                if (matched.contains(name)) continue;
                for (String prefix : entry.getValue()) {
                    if (name.startsWith(prefix)) {
                        ToolInfo ti = new ToolInfo(name, permissions.isToolEnabled(name));
                        DefaultMutableTreeNode toolNode = new DefaultMutableTreeNode(ti);
                        catNode.add(toolNode);
                        toolNodes.put(name, toolNode);
                        allToolNames.add(name);
                        matched.add(name);
                        hasTools = true;
                        break;
                    }
                }
            }
            if (hasTools) {
                rootNode.add(catNode);
                categoryNodes.add(catNode);
            }
        }
        // Unmatched
        java.util.List<String> unmatched = new ArrayList<>();
        for (String name : allNames) if (!matched.contains(name)) unmatched.add(name);
        if (!unmatched.isEmpty()) {
            DefaultMutableTreeNode otherNode = new DefaultMutableTreeNode("Other");
            for (String name : unmatched) {
                ToolInfo ti = new ToolInfo(name, permissions.isToolEnabled(name));
                DefaultMutableTreeNode tn = new DefaultMutableTreeNode(ti);
                otherNode.add(tn);
                toolNodes.put(name, tn);
            }
            rootNode.add(otherNode);
            categoryNodes.add(otherNode);
        }
    }

    private void applyPreset() {
        updatingFromPreset = true;
        boolean custom = presetCombo.getSelectedIndex() == 2;
        for (var entry : toolNodes.entrySet()) {
            ToolInfo ti = (ToolInfo) entry.getValue().getUserObject();
            if (!custom) {
                ti.selected = presetCombo.getSelectedIndex() == 0
                        || PermissionManager.isReadTool(entry.getKey());
            }
            ti.enabled = custom;
        }
        tree.repaint();
        updatingFromPreset = false;
        updateSummary();
    }

    private void checkAll(boolean sel) {
        for (var entry : toolNodes.entrySet()) {
            if (!isProfessional && isProOnly(entry.getKey())) continue;
            ((ToolInfo) entry.getValue().getUserObject()).selected = sel;
        }
        tree.repaint();
    }

    private void setAllRead(boolean readOnly) {
        for (var entry : toolNodes.entrySet()) {
            if (!isProfessional && isProOnly(entry.getKey())) continue;
            ((ToolInfo) entry.getValue().getUserObject()).selected = readOnly
                    ? PermissionManager.isReadTool(entry.getKey())
                    : !PermissionManager.isReadTool(entry.getKey());
        }
        tree.repaint();
    }

    private void applyFilter() {
        String term = searchField.getText().trim().toLowerCase();
        for (var entry : toolNodes.entrySet()) {
            boolean match = term.isEmpty() || entry.getKey().toLowerCase().contains(term);
            ToolInfo ti = (ToolInfo) entry.getValue().getUserObject();
            ti.filteredOut = !match;
        }
        tree.repaint();
    }

    private void updateSummary() {
        int read = 0, write = 0, sens = 0;
        for (String name : allToolNames) {
            if (PermissionManager.isReadTool(name)) read++; else write++;
            if (PermissionManager.isSensitive(name)) sens++;
        }
        summaryLabel.setText(read + " read-only | " + write + " write | " + sens + " sensitive");
    }

    private void applyPermissions() {
        PermissionManager.Level level = levelForIndex(presetCombo.getSelectedIndex());
        permissions.setLevel(level);
        permissions.setBlockSensitive(sensitivityCheck.isSelected());

        if (level == PermissionManager.Level.CUSTOM) {
            for (var entry : toolNodes.entrySet()) {
                if (!isProfessional && isProOnly(entry.getKey())) continue;
                ToolInfo ti = (ToolInfo) entry.getValue().getUserObject();
                if (ti.selected) permissions.enableTool(entry.getKey());
                else permissions.disableTool(entry.getKey());
            }
        }

        int enabled = 0, disabled = 0;
        for (String name : allToolNames) {
            if (permissions.isToolEnabled(name)) enabled++; else disabled++;
        }
        String sens = sensitivityCheck.isSelected() ? " | Sensitive: BLOCKED" : " | Sensitive: ALLOWED";
        statusLabel.setText("Applied: " + level.name() + " | " + enabled + " enabled, " + disabled + " disabled" + sens);
        statusLabel.setForeground(McpColors.GREEN);
    }

    private static boolean isProOnly(String toolName) {
        return toolName.startsWith("scanner_") || toolName.startsWith("collaborator_");
    }

    // Combo order is Read-Write, Read-Only, Custom — deliberately not
    // Level.ordinal() (which is READ_ONLY, READ_WRITE, CUSTOM).
    private static int indexForLevel(PermissionManager.Level level) {
        return switch (level) {
            case READ_WRITE -> 0;
            case READ_ONLY -> 1;
            case CUSTOM -> 2;
        };
    }

    private static PermissionManager.Level levelForIndex(int idx) {
        return switch (idx) {
            case 0 -> PermissionManager.Level.READ_WRITE;
            case 1 -> PermissionManager.Level.READ_ONLY;
            default -> PermissionManager.Level.CUSTOM;
        };
    }

    static class ToolInfo {
        final String name;
        boolean selected;
        boolean enabled = true;
        boolean filteredOut = false;

        ToolInfo(String name, boolean selected) {
            this.name = name;
            this.selected = selected;
        }

        @Override public String toString() { return name; }
    }

    // ── Tree renderer ──

    class ToolTreeRenderer extends DefaultTreeCellRenderer {
        private final JCheckBox checkBox = new JCheckBox();

        ToolTreeRenderer() {
            checkBox.setOpaque(false);
            setLeafIcon(null);
            setClosedIcon(null);
            setOpenIcon(null);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                                                       boolean expanded, boolean leaf, int row, boolean hasFocus) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            Object obj = node.getUserObject();

            if (obj instanceof ToolInfo ti) {
                if (ti.filteredOut) {
                    checkBox.setVisible(false);
                    return checkBox;
                }
                checkBox.setVisible(true);
                checkBox.setText(ti.name);
                checkBox.setEnabled(ti.enabled);
                checkBox.setFont(McpColors.LABEL_FONT);

                boolean blocked = sensitivityCheck.isSelected() && PermissionManager.isSensitive(ti.name);
                boolean proOnly = !isProfessional && isProOnly(ti.name);

                if (proOnly) {
                    checkBox.setForeground(McpColors.GRAY);
                    checkBox.setText("\uD83D\uDD12 PRO " + ti.name);
                    checkBox.setSelected(false);
                    checkBox.setEnabled(false);
                    checkBox.setToolTipText("Requires Burp Suite Professional");
                } else if (blocked) {
                    checkBox.setForeground(McpColors.GRAY);
                    checkBox.setText("\uD83D\uDD12 " + ti.name);
                    checkBox.setSelected(false);
                    checkBox.setEnabled(ti.enabled);
                    checkBox.setToolTipText(null);
                } else if (PermissionManager.isSensitive(ti.name)) {
                    checkBox.setForeground(McpColors.AMBER);
                    checkBox.setText("\uD83D\uDD12 " + ti.name);
                    checkBox.setSelected(ti.selected);
                    checkBox.setEnabled(ti.enabled);
                    checkBox.setToolTipText(null);
                } else if (PermissionManager.isReadTool(ti.name)) {
                    checkBox.setForeground(McpColors.GREEN);
                    checkBox.setSelected(ti.selected);
                    checkBox.setEnabled(ti.enabled);
                    checkBox.setToolTipText(null);
                } else {
                    checkBox.setForeground(UIManager.getColor("Label.foreground"));
                    checkBox.setSelected(ti.selected);
                    checkBox.setEnabled(ti.enabled);
                    checkBox.setToolTipText(null);
                }
                return checkBox;
            }

            // Category node
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            setFont(getFont().deriveFont(Font.BOLD));
            return this;
        }
    }
}
