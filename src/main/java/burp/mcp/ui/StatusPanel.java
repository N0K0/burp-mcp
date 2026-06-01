package burp.mcp.ui;

import burp.mcp.server.McPServer;
import burp.mcp.util.McpConfig;
import burp.mcp.util.MetricsCollector;
import burp.mcp.util.VersionInfo;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Panel showing server status, metrics dashboard, and request log
 * with search, filtering, and export capabilities.
 */
public class StatusPanel extends JPanel {

    private final JLabel serverStatusLabel;
    private final JLabel portLabel;
    private final JLabel addressLabel;
    private final JLabel requestCountLabel;
    private final JLabel requestRateLabel;
    private final JLabel errorRateLabel;
    private final JLabel p95Label;
    private final JLabel uptimeLabel;
    private final JLabel rateLimitedLabel;

    private final JTextArea logArea;
    private final JTextField searchField;
    private final JComboBox<String> levelFilterCombo;
    private final JCheckBox autoScrollCheck;
    private final Timer refreshTimer;

    private McPServer server;
    private String currentLevelFilter = "";

    // Pre-initialize logArea so inner classes in the toolbar can reference it
    {
        logArea = new JTextArea(12, 60);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setBackground(DARKER_BG);
        logArea.setForeground(LIGHT_TEXT);
    }

    private static final Color DARK_BG = new Color(43, 43, 43);
    private static final Color LIGHT_TEXT = new Color(187, 187, 187);
    private static final Color GREEN = new Color(76, 175, 80);
    private static final Color AMBER = new Color(255, 193, 7);
    private static final Color RED = new Color(244, 67, 54);
    private static final Color GRAY = new Color(120, 120, 120);
    private static final Color DARKER_BG = new Color(30, 30, 30);

    public StatusPanel() {
        setLayout(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(10, 10, 10, 10));

        // ── Dashboard section ──
        JPanel dashboardPanel = new JPanel(new GridBagLayout());
        dashboardPanel.setBorder(new TitledBorder("Dashboard"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 8, 3, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        int row = 0;
        serverStatusLabel = addInfoRow(dashboardPanel, gbc, row++, "Status:", "Starting...");
        uptimeLabel = addInfoRow(dashboardPanel, gbc, row++, "Uptime:", "0s");
        addressLabel = addInfoRow(dashboardPanel, gbc, row++, "Bind:", McpConfig.getInstance().getBindAddress());
        portLabel = addInfoRow(dashboardPanel, gbc, row++, "Port:", String.valueOf(McpConfig.getInstance().getPort()));
        requestCountLabel = addInfoRow(dashboardPanel, gbc, row++, "Requests:", "0");
        requestRateLabel = addInfoRow(dashboardPanel, gbc, row++, "Req/min:", "0");
        p95Label = addInfoRow(dashboardPanel, gbc, row++, "P95:", "0ms");
        errorRateLabel = addInfoRow(dashboardPanel, gbc, row++, "Errors:", "0");
        rateLimitedLabel = addInfoRow(dashboardPanel, gbc, row++, "Rate Ltd:", "0");

        // ── Log section ──
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(new TitledBorder("Request Log"));

        // Toolbar
        JPanel logToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));

        logToolbar.add(new JLabel("Filter:"));
        levelFilterCombo = new JComboBox<>(new String[]{"ALL", "INFO", "WARN", "ERROR", "DEBUG"});
        levelFilterCombo.addActionListener(e -> {
            currentLevelFilter = "ALL".equals(levelFilterCombo.getSelectedItem()) ? ""
                    : (String) levelFilterCombo.getSelectedItem();
        });
        logToolbar.add(levelFilterCombo);

        logToolbar.add(new JLabel("Search:"));
        searchField = new JTextField(12);
        searchField.addActionListener(e -> highlightSearch());
        logToolbar.add(searchField);

        JButton searchBtn = new JButton("Find");
        searchBtn.addActionListener(e -> highlightSearch());
        logToolbar.add(searchBtn);

        autoScrollCheck = new JCheckBox("Auto-scroll", true);
        logToolbar.add(autoScrollCheck);

        JButton exportBtn = new JButton("Export");
        exportBtn.addActionListener(e -> exportLog());
        logToolbar.add(exportBtn);

        JButton copyBtn = new JButton("Copy");
        copyBtn.addActionListener(e -> {
            String sel = logArea.getSelectedText();
            if (sel == null || sel.isEmpty()) sel = logArea.getText();
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(sel), null);
        });
        logToolbar.add(copyBtn);

        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> logArea.setText(""));
        logToolbar.add(clearBtn);

        logPanel.add(logToolbar, BorderLayout.NORTH);

        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        logPanel.add(logScroll, BorderLayout.CENTER);

        // Layout
        add(dashboardPanel, BorderLayout.NORTH);
        add(logPanel, BorderLayout.CENTER);

        refreshTimer = new Timer(2000, e -> refresh());
    }

    public void setServer(McPServer server) {
        this.server = server;
        refresh();
        refreshTimer.start();
    }

    public void appendLog(String entry) {
        SwingUtilities.invokeLater(() -> {
            // Color by level
            String styled = entry;
            Color color = LIGHT_TEXT;
            if (entry.contains("| WARN |")) color = AMBER;
            else if (entry.contains("| ERROR |")) color = RED;
            else if (entry.contains("| DEBUG |")) color = GRAY;
            else if (entry.contains("| INFO |")) color = LIGHT_TEXT;

            // Apply level filter
            if (!currentLevelFilter.isEmpty()
                    && !entry.toUpperCase().contains("| " + currentLevelFilter.toUpperCase() + " |")) {
                return;
            }

            // Max line limit: 10,000
            if (logArea.getLineCount() > 10_000) {
                try {
                    int endOfFirst10pct = logArea.getLineEndOffset(
                            Math.max(0, logArea.getLineCount() / 10));
                    logArea.replaceRange("", 0, endOfFirst10pct);
                } catch (BadLocationException ignored) {}
            }

            logArea.append(entry + "\n");
            if (autoScrollCheck.isSelected()) {
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }
        });
    }

    private void highlightSearch() {
        String term = searchField.getText().trim();
        Highlighter h = logArea.getHighlighter();
        h.removeAllHighlights();
        if (term.isEmpty()) return;

        String text = logArea.getText();
        int idx = 0;
        Highlighter.HighlightPainter painter =
                new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 255, 0, 80));
        while ((idx = text.indexOf(term, idx)) >= 0) {
            try {
                h.addHighlight(idx, idx + term.length(), painter);
            } catch (BadLocationException ignored) {}
            idx += term.length();
        }
    }

    private void exportLog() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("burp-mcp-log.txt"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try (FileWriter fw = new FileWriter(chooser.getSelectedFile())) {
                fw.write(logArea.getText());
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Failed to export: " + ex.getMessage(),
                        "Export Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void refresh() {
        if (server == null) return;

        MetricsCollector m = server.getMetrics();
        SwingUtilities.invokeLater(() -> {
            boolean running = server.isRunning();
            if (running) {
                serverStatusLabel.setText("Running");
                serverStatusLabel.setForeground(GREEN);
            } else {
                serverStatusLabel.setText("Stopped");
                serverStatusLabel.setForeground(RED);
            }

            requestCountLabel.setText(String.valueOf(server.getRequestCount()));

            if (m != null) {
                uptimeLabel.setText(formatUptime(m.getUptimeSeconds()));
                requestRateLabel.setText(String.valueOf(m.getRequestsPerMinute()));
                p95Label.setText(m.getPercentileMs(95) + "ms");
                errorRateLabel.setText(String.valueOf(m.getErrorCount()));
                rateLimitedLabel.setText(String.valueOf(m.getRateLimitedCount()));
            }

            portLabel.setText(String.valueOf(McpConfig.getInstance().getPort()));
            addressLabel.setText(McpConfig.getInstance().getBindAddress());
        });
    }

    private static String formatUptime(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }

    public void stop() {
        refreshTimer.stop();
    }

    private JLabel addInfoRow(JPanel panel, GridBagConstraints gbc, int row, String label, String value) {
        gbc.gridy = row;

        gbc.gridx = 0;
        gbc.weightx = 0;
        JLabel lbl = new JLabel(label);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD));
        panel.add(lbl, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        JLabel val = new JLabel(value);
        panel.add(val, gbc);

        return val;
    }
}
