package burp.mcp.ui;

import burp.mcp.util.ApprovalManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Builds the interactive card for one pending approval. Used by both the
 * Approvals tab and the popup dialog.
 */
final class ApprovalCard {

    private ApprovalCard() {
    }

    static JPanel build(ApprovalManager manager, ApprovalManager.PendingApproval pending) {
        JPanel card = new JPanel(new BorderLayout(4, 6));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(McpColors.AMBER, 1),
                new EmptyBorder(8, 8, 8, 8)));

        // ── Description ──
        JPanel info = new JPanel();
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));

        JLabel title = new JLabel(pending.getToolName()
                + "   (" + shortId(pending.getId()) + " · " + time(pending.getCreatedAt())
                + (pending.isWaiting() ? " · client waiting" : " · no client waiting") + ")");
        title.setFont(McpColors.BOLD_LABEL);
        info.add(title);

        if (pending.isPermissionPrompt()) {
            JLabel reason = new JLabel("Permission: " + safe(pending.getPermissionReason()));
            reason.setForeground(McpColors.AMBER);
            reason.setFont(McpColors.LABEL_FONT);
            info.add(reason);
        }
        if (pending.isScopePrompt()) {
            JLabel scope = new JLabel("Out of scope: " + String.join(", ", pending.getOutOfScopeTargets()));
            scope.setForeground(McpColors.RED);
            scope.setFont(McpColors.LABEL_FONT);
            info.add(scope);
        }
        if (!pending.getTargets().isEmpty()) {
            JLabel targets = new JLabel("Targets: " + String.join(", ", pending.getTargets()));
            targets.setFont(McpColors.LABEL_FONT);
            info.add(targets);
        }

        JTextArea args = new JTextArea(pending.getArgsSummary());
        args.setEditable(false);
        args.setLineWrap(true);
        args.setWrapStyleWord(false);
        args.setFont(McpColors.MONO_SMALL);
        args.setRows(2);
        args.setBackground(UIManager.getColor("Panel.background"));
        args.setBorder(new EmptyBorder(2, 0, 2, 0));
        info.add(args);
        card.add(info, BorderLayout.NORTH);

        // ── Decision controls ──
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));

        final ApprovalManager.PermissionDecision[] permissionChoice = {null};
        final ApprovalManager.ScopeDecision[] scopeChoice = {null};
        final JTextField reasonField = new JTextField();
        reasonField.setToolTipText(McpColors.tooltip(
                "Optional reason. When you deny, this text is returned to the agent harness."));

        JButton submit = new JButton("Submit decision");
        submit.setEnabled(false);
        Runnable refreshSubmit = () -> submit.setEnabled(
                (!pending.isPermissionPrompt() || permissionChoice[0] != null)
                        && (!pending.isScopePrompt() || scopeChoice[0] != null));

        if (pending.isPermissionPrompt()) {
            controls.add(choiceRow("Permission:", new String[]{"Allow once", "Allow for session", "Deny"},
                    index -> {
                        permissionChoice[0] = switch (index) {
                            case 0 -> ApprovalManager.PermissionDecision.ALLOW_ONCE;
                            case 1 -> ApprovalManager.PermissionDecision.ALLOW_SESSION;
                            default -> ApprovalManager.PermissionDecision.DENY;
                        };
                        refreshSubmit.run();
                    }));
        }
        if (pending.isScopePrompt()) {
            controls.add(choiceRow("Target scope:",
                    new String[]{"Add to scope", "Allow once", "Allow for session", "Deny"},
                    index -> {
                        scopeChoice[0] = switch (index) {
                            case 0 -> ApprovalManager.ScopeDecision.ADD_TO_SCOPE;
                            case 1 -> ApprovalManager.ScopeDecision.ALLOW_ONCE;
                            case 2 -> ApprovalManager.ScopeDecision.ALLOW_SESSION;
                            default -> ApprovalManager.ScopeDecision.DENY;
                        };
                        refreshSubmit.run();
                    }));
        }

        JPanel reasonRow = new JPanel(new BorderLayout(6, 0));
        reasonRow.add(new JLabel("Reason:"), BorderLayout.WEST);
        reasonRow.add(reasonField, BorderLayout.CENTER);
        reasonRow.add(submit, BorderLayout.EAST);
        controls.add(reasonRow);

        submit.addActionListener(e -> {
            String reason = reasonField.getText().trim();
            boolean resolved = manager.resolve(pending.getId(),
                    new ApprovalManager.Decision(permissionChoice[0], scopeChoice[0],
                            reason.isEmpty() ? null : reason));
            if (!resolved) {
                submit.setText("Already decided");
                submit.setEnabled(false);
            }
        });

        card.add(controls, BorderLayout.CENTER);
        return card;
    }

    private interface ChoiceHandler {
        void chosen(int index);
    }

    private static JPanel choiceRow(String label, String[] options, ChoiceHandler handler) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JLabel title = new JLabel(label);
        title.setFont(McpColors.BOLD_LABEL);
        row.add(title);
        ButtonGroup group = new ButtonGroup();
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            JRadioButton button = new JRadioButton(options[i]);
            button.setFont(McpColors.LABEL_FONT);
            if ("Deny".equals(options[i])) {
                button.setForeground(McpColors.RED);
            }
            button.addActionListener(e -> handler.chosen(index));
            group.add(button);
            row.add(button);
        }
        return row;
    }

    private static String shortId(String id) {
        return id == null ? "?" : id.substring(0, Math.min(8, id.length()));
    }

    private static String time(long epochMs) {
        return new SimpleDateFormat("HH:mm:ss").format(new Date(epochMs));
    }

    private static String safe(String text) {
        return text == null || text.isBlank() ? "(no reason given)" : text;
    }
}
