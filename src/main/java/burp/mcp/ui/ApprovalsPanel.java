package burp.mcp.ui;

import burp.api.montoya.MontoyaApi;
import burp.mcp.util.ApprovalManager;
import burp.mcp.util.McpConfig;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Approvals tab: pending operator decisions for prompt-gated tools and
 * out-of-scope targets, active session grants, and a short decision history.
 * New requests can additionally pop a non-modal dialog.
 */
public class ApprovalsPanel extends JPanel implements ApprovalManager.Listener {

    private final MontoyaApi api;
    private final ApprovalManager manager;

    private final JPanel pendingList = new JPanel();
    private final JPanel grantsList = new JPanel();
    private final DefaultListModel<String> historyModel = new DefaultListModel<>();
    private final JLabel headerLabel = new JLabel();
    private final JLabel statusLabel = new JLabel(" ");
    private final JCheckBox popupCheck = new JCheckBox("Popup on new approvals");

    private volatile Consumer<Integer> badgeConsumer;
    private JDialog popupDialog;
    private JPanel popupContent;
    private ApprovalManager.PendingApproval currentPopupPending;
    private final Set<String> dismissedPopups = ConcurrentHashMap.newKeySet();

    public ApprovalsPanel(MontoyaApi api, ApprovalManager manager) {
        this.api = api;
        this.manager = manager;

        setLayout(new BorderLayout(8, 8));
        setBorder(new EmptyBorder(10, 10, 10, 10));

        // ── Toolbar ──
        JPanel toolbar = new JPanel(new BorderLayout(8, 4));
        headerLabel.setFont(McpColors.BOLD_LABEL);
        toolbar.add(headerLabel, BorderLayout.WEST);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        McpConfig cfg = McpConfig.getInstance();
        popupCheck.setSelected(cfg == null || cfg.isApprovalPopupEnabled());
        popupCheck.setToolTipText(McpColors.tooltip(
                "Show a dismissible dialog when a new approval request arrives. "
                + "Requests are always visible in this tab."));
        popupCheck.addActionListener(e -> {
            McpConfig c = McpConfig.getInstance();
            if (c != null) {
                c.setApprovalPopupEnabled(popupCheck.isSelected());
            }
            if (!popupCheck.isSelected()) {
                hidePopup();
            } else {
                refreshPopup();
            }
        });
        JButton clearGrants = new JButton("Clear session grants");
        clearGrants.setToolTipText(McpColors.tooltip(
                "Revoke every 'Allow for session' decision. Pending requests are not affected."));
        clearGrants.addActionListener(e -> {
            manager.clearSessionGrants();
            statusLabel.setText("Session grants cleared.");
            statusLabel.setForeground(McpColors.GREEN);
        });
        actions.add(popupCheck);
        actions.add(clearGrants);
        toolbar.add(actions, BorderLayout.EAST);
        add(toolbar, BorderLayout.NORTH);

        // ── Pending cards ──
        pendingList.setLayout(new BoxLayout(pendingList, BoxLayout.Y_AXIS));
        JScrollPane pendingScroll = new JScrollPane(pendingList);
        pendingScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        pendingScroll.setBorder(new TitledBorder("Pending requests"));

        // ── Grants + history ──
        grantsList.setLayout(new BoxLayout(grantsList, BoxLayout.Y_AXIS));
        JScrollPane grantsScroll = new JScrollPane(grantsList);
        grantsScroll.setBorder(new TitledBorder("Active session grants"));

        JList<String> historyList = new JList<>(historyModel);
        historyList.setFont(McpColors.MONO_SMALL);
        JScrollPane historyScroll = new JScrollPane(historyList);
        historyScroll.setBorder(new TitledBorder("Recent decisions"));

        JTabbedPane bottomTabs = new JTabbedPane();
        bottomTabs.addTab("Session grants", grantsScroll);
        bottomTabs.addTab("Recent decisions", historyScroll);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, pendingScroll, bottomTabs);
        split.setResizeWeight(0.7);
        add(split, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        manager.addListener(this);
        rebuild();
    }

    /** Tab badge callback: receives the current pending-request count. */
    public void setBadgeConsumer(Consumer<Integer> consumer) {
        this.badgeConsumer = consumer;
        rebuild();
    }

    /** Detach from the approval manager and close the popup (extension unload). */
    public void dispose() {
        manager.removeListener(this);
        JDialog dialog = popupDialog;
        if (dialog != null) {
            try {
                SwingUtilities.invokeLater(dialog::dispose);
            } catch (Exception ignored) {
                // Never fail an unload on UI cleanup.
            }
        }
    }

    // ── ApprovalManager.Listener (any thread) ──

    @Override
    public void onPendingAdded(ApprovalManager.PendingApproval pending) {
        onEdt(() -> {
            rebuild();
            refreshPopup();
        });
    }

    @Override
    public void onPendingResolved(ApprovalManager.PendingApproval pending,
                                  ApprovalManager.Decision decision) {
        String line = time() + "  " + pending.getToolName() + "  →  "
                + describe(decision) + reasonSuffix(decision);
        onEdt(() -> {
            historyModel.add(0, line);
            trimHistory();
            rebuild();
        });
    }

    @Override
    public void onPendingExpired(ApprovalManager.PendingApproval pending) {
        String line = time() + "  " + pending.getToolName() + "  →  expired / cancelled";
        onEdt(() -> {
            historyModel.add(0, line);
            trimHistory();
            rebuild();
        });
    }

    @Override
    public void onGrantsChanged() {
        onEdt(this::rebuild);
    }

    // ── Rebuild ──

    private void rebuild() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::rebuild);
            return;
        }
        var pending = manager.getPending();
        headerLabel.setText("Pending approvals: " + pending.size());
        headerLabel.setForeground(pending.isEmpty() ? UIManager.getColor("Label.foreground") : McpColors.AMBER);

        pendingList.removeAll();
        if (pending.isEmpty()) {
            JLabel empty = new JLabel("No pending requests.");
            empty.setForeground(McpColors.GRAY);
            empty.setBorder(new EmptyBorder(8, 8, 8, 8));
            pendingList.add(empty);
        } else {
            for (ApprovalManager.PendingApproval p : pending) {
                JPanel card = ApprovalCard.build(manager, p);
                card.setAlignmentX(Component.LEFT_ALIGNMENT);
                pendingList.add(card);
                pendingList.add(Box.createVerticalStrut(8));
            }
        }
        pendingList.revalidate();
        pendingList.repaint();

        rebuildGrants();

        Consumer<Integer> badge = badgeConsumer;
        if (badge != null) {
            try {
                badge.accept(pending.size());
            } catch (Exception ignored) {
            }
        }
        refreshPopup();
    }

    private void rebuildGrants() {
        grantsList.removeAll();
        Set<String> toolGrants = manager.getToolGrants();
        Set<String> originGrants = manager.getOriginGrants();
        if (toolGrants.isEmpty() && originGrants.isEmpty()) {
            JLabel empty = new JLabel("No active session grants.");
            empty.setForeground(McpColors.GRAY);
            empty.setBorder(new EmptyBorder(6, 8, 6, 8));
            grantsList.add(empty);
        } else {
            for (String tool : toolGrants) {
                grantsList.add(grantRow("Tool", tool, () -> manager.revokeToolGrant(tool)));
            }
            for (String origin : originGrants) {
                grantsList.add(grantRow("Target", origin, () -> manager.revokeOriginGrant(origin)));
            }
        }
        grantsList.revalidate();
        grantsList.repaint();
    }

    private JPanel grantRow(String kind, String value, Runnable revoke) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBorder(new EmptyBorder(2, 8, 2, 8));
        JLabel label = new JLabel(kind + ": " + value);
        label.setFont(McpColors.MONO_SMALL);
        JButton button = new JButton("Revoke");
        button.setFont(McpColors.LABEL_FONT);
        button.addActionListener(e -> revoke.run());
        row.add(label, BorderLayout.CENTER);
        row.add(button, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height + 6));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }

    private void trimHistory() {
        while (historyModel.size() > 50) {
            historyModel.remove(historyModel.size() - 1);
        }
    }

    // ── Popup dialog ──

    private void refreshPopup() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::refreshPopup);
            return;
        }
        if (!popupCheck.isSelected()) {
            hidePopup();
            return;
        }
        ApprovalManager.PendingApproval next = manager.getPending().stream()
                .filter(p -> !dismissedPopups.contains(p.getId()))
                .findFirst()
                .orElse(null);
        if (next == null) {
            hidePopup();
        } else {
            showPopup(next);
        }
    }

    private void showPopup(ApprovalManager.PendingApproval pending) {
        if (popupDialog == null) {
            Window owner = suiteFrame();
            popupDialog = owner != null
                    ? new JDialog(owner, "Burp MCP — approval required")
                    : new JDialog((Frame) null, "Burp MCP — approval required");
            popupDialog.setModal(false);
            popupDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            popupDialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent e) {
                    if (currentPopupPending != null) {
                        dismissedPopups.add(currentPopupPending.getId());
                    }
                    hidePopup();
                    refreshPopup();
                }
            });
            popupContent = new JPanel(new BorderLayout(6, 6));
            popupContent.setBorder(new EmptyBorder(8, 8, 8, 8));
            popupDialog.setContentPane(popupContent);
            popupDialog.setMinimumSize(new Dimension(560, 220));
        }
        currentPopupPending = pending;
        popupContent.removeAll();
        popupContent.add(ApprovalCard.build(manager, pending), BorderLayout.CENTER);
        JButton dismiss = new JButton("Dismiss (stays in the Approvals tab)");
        dismiss.addActionListener(e -> {
            dismissedPopups.add(pending.getId());
            hidePopup();
            refreshPopup();
        });
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        south.add(dismiss);
        popupContent.add(south, BorderLayout.SOUTH);
        popupDialog.pack();
        popupDialog.setLocationRelativeTo(suiteFrame());
        popupDialog.setVisible(true);
        popupDialog.toFront();
    }

    private void hidePopup() {
        if (popupDialog != null) {
            popupDialog.setVisible(false);
        }
    }

    private Window suiteFrame() {
        try {
            return api.userInterface().swingUtils().suiteFrame();
        } catch (Exception e) {
            return null;
        }
    }

    // ── Helpers ──

    private static void onEdt(Runnable runnable) {
        // Always defer: listener callbacks may fire while a Swing action is
        // still being dispatched (e.g. resolve() from a card button).
        SwingUtilities.invokeLater(runnable);
    }

    private static String describe(ApprovalManager.Decision decision) {
        if (decision == null) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder();
        if (decision.getPermission() != null) {
            sb.append("permission=").append(decision.getPermission());
        }
        if (decision.getScope() != null) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("scope=").append(decision.getScope());
        }
        return sb.length() == 0 ? "resolved" : sb.toString();
    }

    private static String reasonSuffix(ApprovalManager.Decision decision) {
        if (decision != null && decision.getReason() != null && !decision.getReason().isBlank()) {
            return "  —  \"" + decision.getReason() + "\"";
        }
        return "";
    }

    private static String time() {
        return new SimpleDateFormat("HH:mm:ss").format(new Date());
    }
}
