package burp.mcp.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.BurpSuiteEdition;
import burp.mcp.tool.McpToolRegistry;
import burp.mcp.util.McpConfig;
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
 * tools as leaf nodes with a per-tool Allow / Prompt / Deny policy in CUSTOM
 * mode (click to cycle). Also controls Burp target-scope enforcement.
 */
public class PermissionsPanel extends JPanel {

    private final PermissionManager permissions;
    private final McpToolRegistry registry;
    private final boolean isProfessional;

    private final JComboBox<String> presetCombo;
    private final JComboBox<String> scopeCombo;
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

    /** Preset combo order: Read-Write, Read-Only, Prompt, Custom. */
    private static final int IDX_READ_WRITE = 0;
    private static final int IDX_READ_ONLY = 1;
    private static final int IDX_PROMPT = 2;
    private static final int IDX_CUSTOM = 3;

    private static final LinkedHashMap<String, String[]> CATEGORIES = new LinkedHashMap<>();
    static {
        CATEGORIES.put("Info & Metrics", new String[]{"burp_info", "burp_metrics", "config_list_preferences", "project_"});
        CATEGORIES.put("HTTP Request/Response", new String[]{"http_", "logger_add"});
        CATEGORIES.put("Proxy & WebSocket", new String[]{"proxy_", "websocket_"});
        CATEGORIES.put("Sitemap & Scope", new String[]{"sitemap_", "scope_"});
        CATEGORIES.put("Decoder, Cookies & Analysis", new String[]{"decoder_", "cookie_", "http_diff", "http_keyword", "http_send_to_"});
        CATEGORIES.put("Config", new String[]{"config_get", "config_set"});
        CATEGORIES.put("Scanner", new String[]{"scanner_"});
        CATEGORIES.put("Collaborator", new String[]{"collaborator_"});
        CATEGORIES.put("Organizer", new String[]{"organizer_"});
        CATEGORIES.put("Task Engine", new String[]{"task_engine_"});
    }

    public PermissionsPanel(MontoyaApi api, PermissionManager permissions, McpToolRegistry registry) {
        this.permissions = permissions;
        this.registry = registry;
        this.isProfessional = api.burpSuite().version().edition() == BurpSuiteEdition.PROFESSIONAL;

        setLayout(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(10, 10, 10, 10));

        // ── Top: presets + sensitivity + scope enforcement ──
        JPanel topPanel = new JPanel(new GridBagLayout());
        topPanel.setBorder(new TitledBorder("Access Control"));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 8, 4, 8);
        g.anchor = GridBagConstraints.WEST;

        g.gridx = 0; g.gridy = 0; g.weightx = 0;
        topPanel.add(new JLabel("Mode:"), g);
        g.gridx = 1; g.weightx = 1.0; g.fill = GridBagConstraints.HORIZONTAL;
        presetCombo = new JComboBox<>(new String[]{
                "Read-Write (all tools)",
                "Read-Only (query/list/decode)",
                "Prompt (writes ask)",
                "Custom (per-tool policy)"
        });
        presetCombo.setSelectedIndex(indexForLevel(permissions.getLevel()));
        presetCombo.addActionListener(e -> applyPreset());
        topPanel.add(presetCombo, g);

        g.gridwidth = 2; g.gridx = 0; g.gridy = 1;
        sensitivityCheck = new JCheckBox("Block sensitive ops (scope_set, config_set, scanner_*, task_engine_set, proxy_toggle_intercept)");
        sensitivityCheck.setSelected(permissions.isBlockSensitive());
        sensitivityCheck.addActionListener(e -> { applyPreset(); });
        topPanel.add(sensitivityCheck, g);

        g.gridy = 2; g.gridwidth = 1; g.gridx = 0; g.weightx = 0;
        topPanel.add(new JLabel("Target scope:"), g);
        g.gridx = 1; g.weightx = 1.0;
        scopeCombo = new JComboBox<>(new String[]{
                "Prompt when out of scope (Add to scope / allow once / allow for session)",
                "Deny out-of-scope traffic",
                "Off (no scope checks)"
        });
        scopeCombo.setSelectedIndex(scopeIndexFor(McpConfig.getInstance() != null
                ? McpConfig.getInstance().getScopeEnforcement() : "prompt"));
        scopeCombo.setToolTipText(McpColors.tooltip("Burp's target scope gates traffic-sending tools "
                + "(http_send_request, http_send_requests, logger_add, scanner starts). With Prompt, "
                + "an out-of-scope call asks here before it runs; Allow for session grants the origin "
                + "until the extension is reloaded. Note: an empty Burp scope means every send counts "
                + "as out-of-scope."));
        topPanel.add(scopeCombo, g);

        summaryLabel = new JLabel();
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.ITALIC, 11));
        g.gridy = 3; g.gridx = 0; g.gridwidth = 2;
        topPanel.add(summaryLabel, g);

        // ── Quick buttons + search ──
        JPanel quickPanel = new JPanel(new BorderLayout(5, 0));
        quickPanel.setBorder(new EmptyBorder(5, 8, 5, 8));
        JPanel quickBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        JButton allRead = new JButton("All Read");
        allRead.addActionListener(e -> setAllRead(true));
        JButton allWrite = new JButton("All Write");
        allWrite.addActionListener(e -> setAllRead(false));
        JButton selectAll = new JButton("Allow All");
        selectAll.addActionListener(e -> checkAll(true));
        JButton deselectAll = new JButton("Deny All");
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
        tree.setToolTipText(McpColors.tooltip("Custom mode: click a tool to cycle Allow → Prompt → Deny."));
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (updatingFromPreset) return;
                if (presetCombo.getSelectedIndex() != IDX_CUSTOM) return;
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path == null) return;
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                if (node.getUserObject() instanceof ToolInfo ti) {
                    ti.policy = nextPolicy(ti.policy);
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
                        ToolInfo ti = new ToolInfo(name, permissions.getToolPolicy(name));
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
                ToolInfo ti = new ToolInfo(name, permissions.getToolPolicy(name));
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
        int idx = presetCombo.getSelectedIndex();
        boolean custom = idx == IDX_CUSTOM;
        for (var entry : toolNodes.entrySet()) {
            ToolInfo ti = (ToolInfo) entry.getValue().getUserObject();
            ti.enabled = custom;
            if (!custom) {
                if (idx == IDX_READ_WRITE) {
                    ti.policy = PermissionManager.Policy.ALLOW;
                } else if (idx == IDX_READ_ONLY) {
                    ti.policy = PermissionManager.isReadTool(entry.getKey())
                            ? PermissionManager.Policy.ALLOW : PermissionManager.Policy.DENY;
                } else { // IDX_PROMPT
                    ti.policy = PermissionManager.isReadTool(entry.getKey())
                            ? PermissionManager.Policy.ALLOW : PermissionManager.Policy.PROMPT;
                }
            }
        }
        tree.repaint();
        updatingFromPreset = false;
        updateSummary();
    }

    private void checkAll(boolean allow) {
        for (var entry : toolNodes.entrySet()) {
            if (!isProfessional && isProOnly(entry.getKey())) continue;
            ((ToolInfo) entry.getValue().getUserObject()).policy =
                    allow ? PermissionManager.Policy.ALLOW : PermissionManager.Policy.DENY;
        }
        tree.repaint();
        updateSummary();
    }

    private void setAllRead(boolean readOnly) {
        for (var entry : toolNodes.entrySet()) {
            if (!isProfessional && isProOnly(entry.getKey())) continue;
            boolean isRead = PermissionManager.isReadTool(entry.getKey());
            ((ToolInfo) entry.getValue().getUserObject()).policy = isRead == readOnly
                    ? PermissionManager.Policy.ALLOW : PermissionManager.Policy.DENY;
        }
        tree.repaint();
        updateSummary();
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
        int read = 0, write = 0, sens = 0, prompt = 0, deny = 0;
        for (String name : allToolNames) {
            if (PermissionManager.isReadTool(name)) read++; else write++;
            if (PermissionManager.isSensitive(name)) sens++;
            ToolInfo ti = treeInfo(name);
            if (ti != null) {
                if (ti.policy == PermissionManager.Policy.PROMPT) prompt++;
                else if (ti.policy == PermissionManager.Policy.DENY) deny++;
            }
        }
        summaryLabel.setText(read + " read-only | " + write + " write | " + sens + " sensitive | "
                + prompt + " prompt | " + deny + " denied");
    }

    private ToolInfo treeInfo(String name) {
        DefaultMutableTreeNode node = toolNodes.get(name);
        return node != null && node.getUserObject() instanceof ToolInfo ti ? ti : null;
    }

    private void applyPermissions() {
        PermissionManager.Level level = levelForIndex(presetCombo.getSelectedIndex());
        permissions.setLevel(level);
        permissions.setBlockSensitive(sensitivityCheck.isSelected());

        if (level == PermissionManager.Level.CUSTOM) {
            Map<String, PermissionManager.Policy> policies = new HashMap<>();
            for (var entry : toolNodes.entrySet()) {
                if (!isProfessional && isProOnly(entry.getKey())) continue;
                ToolInfo ti = (ToolInfo) entry.getValue().getUserObject();
                policies.put(entry.getKey(), ti.policy);
            }
            permissions.setToolPolicies(policies);
        }

        // Target-scope enforcement (read live by the access gate).
        try {
            McpConfig cfg = McpConfig.getInstance();
            if (cfg != null) {
                cfg.setScopeEnforcement(scopeForIndex(scopeCombo.getSelectedIndex()));
            }
        } catch (Exception ignored) {
            // Config unavailable — the gate keeps its current setting.
        }

        int allow = 0, prompt = 0, deny = 0;
        for (String name : allToolNames) {
            ToolInfo ti = treeInfo(name);
            if (ti == null) continue;
            switch (ti.policy) {
                case ALLOW -> allow++;
                case PROMPT -> prompt++;
                case DENY -> deny++;
            }
        }
        String sens = sensitivityCheck.isSelected() ? " | Sensitive: BLOCKED" : " | Sensitive: ALLOWED";
        String scope = switch (scopeCombo.getSelectedIndex()) {
            case 1 -> " | Scope: DENY";
            case 2 -> " | Scope: OFF";
            default -> " | Scope: PROMPT";
        };
        statusLabel.setText("Applied: " + level.name() + " | " + allow + " allow, " + prompt
                + " prompt, " + deny + " deny" + sens + scope);
        statusLabel.setForeground(McpColors.GREEN);
    }

    private static boolean isProOnly(String toolName) {
        return toolName.startsWith("scanner_") || toolName.startsWith("collaborator_");
    }

    static PermissionManager.Policy nextPolicy(PermissionManager.Policy policy) {
        return switch (policy) {
            case ALLOW -> PermissionManager.Policy.PROMPT;
            case PROMPT -> PermissionManager.Policy.DENY;
            case DENY -> PermissionManager.Policy.ALLOW;
        };
    }

    // Preset combo order is Read-Write, Read-Only, Prompt, Custom — deliberately
    // not Level.ordinal() (READ_ONLY, READ_WRITE, PROMPT, CUSTOM).
    private static int indexForLevel(PermissionManager.Level level) {
        return switch (level) {
            case READ_WRITE -> IDX_READ_WRITE;
            case READ_ONLY -> IDX_READ_ONLY;
            case PROMPT -> IDX_PROMPT;
            case CUSTOM -> IDX_CUSTOM;
        };
    }

    private static PermissionManager.Level levelForIndex(int idx) {
        return switch (idx) {
            case IDX_READ_WRITE -> PermissionManager.Level.READ_WRITE;
            case IDX_READ_ONLY -> PermissionManager.Level.READ_ONLY;
            case IDX_PROMPT -> PermissionManager.Level.PROMPT;
            default -> PermissionManager.Level.CUSTOM;
        };
    }

    private static int scopeIndexFor(String enforcement) {
        return switch (enforcement == null ? "prompt" : enforcement) {
            case "deny" -> 1;
            case "off" -> 2;
            default -> 0;
        };
    }

    private static String scopeForIndex(int idx) {
        return switch (idx) {
            case 1 -> "deny";
            case 2 -> "off";
            default -> "prompt";
        };
    }

    static class ToolInfo {
        final String name;
        PermissionManager.Policy policy;
        boolean enabled = true;
        boolean filteredOut = false;

        ToolInfo(String name, PermissionManager.Policy policy) {
            this.name = name;
            this.policy = policy;
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
                    return checkBox;
                }
                if (blocked) {
                    checkBox.setForeground(McpColors.GRAY);
                    checkBox.setText("\uD83D\uDD12 " + ti.name);
                    checkBox.setSelected(false);
                    checkBox.setToolTipText("Blocked by the sensitivity gate");
                    return checkBox;
                }

                String prefix = PermissionManager.isSensitive(ti.name) ? "\uD83D\uDD12 " : "";
                switch (ti.policy) {
                    case PROMPT -> {
                        checkBox.setForeground(McpColors.AMBER);
                        checkBox.setText(prefix + "\u2753 " + ti.name);
                        checkBox.setSelected(false);
                        checkBox.setToolTipText("Operator approval required");
                    }
                    case DENY -> {
                        checkBox.setForeground(McpColors.RED);
                        checkBox.setText(prefix + "\u2715 " + ti.name);
                        checkBox.setSelected(false);
                        checkBox.setToolTipText("Denied");
                    }
                    default -> {
                        checkBox.setForeground(PermissionManager.isSensitive(ti.name)
                                ? McpColors.AMBER
                                : (PermissionManager.isReadTool(ti.name) ? McpColors.GREEN : UIManager.getColor("Label.foreground")));
                        checkBox.setText(prefix + ti.name);
                        checkBox.setSelected(true);
                        checkBox.setToolTipText(null);
                    }
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
