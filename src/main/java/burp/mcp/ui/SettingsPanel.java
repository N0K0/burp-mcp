package burp.mcp.ui;

import burp.mcp.util.McpConfig;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Panel for editing extension preferences with inline validation,
 * logical group separators, export/import, and reset-to-defaults.
 */
public class SettingsPanel extends JPanel {

    // Server group
    private final JTextField portField;
    private final JTextField bindAddressField;
    private final JTextField threadPoolField;
    private final JTextField maxQueueField;
    private final JCheckBox socketEnabledCheck;
    private final JTextField socketPathField;

    // Limits group
    private final JTextField maxResponseBodyField;
    private final JTextField maxSitemapField;
    private final JTextField requestTimeoutField;
    private final JTextField cacheTtlField;
    private final JTextField rateLimitField;
    private final JTextField maxConnPerIpField;

    // Feature toggles
    private final JCheckBox includeRequestBodyCheck;
    private final JCheckBox includeResponseBodyCheck;
    private final JCheckBox metricsEnabledCheck;
    private final JCheckBox cacheEnabledCheck;

    // Operator approvals
    private final JTextField approvalWaitField;
    private final JTextField approvalTtlField;

    // Logging group
    private final JComboBox<String> logLevelCombo;
    private final JTextField loggingFilePathField;

    // Auth
    private final JCheckBox authEnabledCheck;
    private final JPasswordField tokenField;
    private final JCheckBox showTokenCheck;

    // TLS
    private final JCheckBox tlsEnabledCheck;
    private final JComboBox<String> tlsModeCombo;
    private final JTextField tlsKeystorePathField;
    private final JPasswordField tlsKeystorePasswordField;

    private JLabel statusLabel;

    // Pre-init so inner classes can reference it
    {
        statusLabel = new JLabel("");
    }

    public SettingsPanel() {
        setLayout(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel allSections = new JPanel();
        allSections.setLayout(new BoxLayout(allSections, BoxLayout.Y_AXIS));

        McpConfig cfg = McpConfig.getInstance();

        // ── Server ──
        JPanel serverPanel = new JPanel(new GridBagLayout());
        serverPanel.setBorder(new TitledBorder("Server (press Restart on Status to apply)"));
        GridBagConstraints g = grid();
        portField = addValidatedRow(serverPanel, g, 0, "Port:", String.valueOf(cfg.getPort()), "1-65535");
        bindAddressField = addRow(serverPanel, g, 1, "Bind Address:", cfg.getBindAddress(), "e.g. 127.0.0.1 or 0.0.0.0");
        threadPoolField = addValidatedRow(serverPanel, g, 2, "Thread Pool Size:", String.valueOf(cfg.getThreadPoolSize()), "1-50");
        maxQueueField = addValidatedRow(serverPanel, g, 3, "Max Queue Size:", String.valueOf(cfg.getMaxQueueSize()), "1-1000");

        g.gridx = 0; g.weightx = 0;
        socketEnabledCheck = checkbox(serverPanel, g, 4, "Enable Unix socket (per-project, no port clashes)", cfg.isSocketEnabled());
        socketPathField = addRow(serverPanel, g, 5, "Socket Path:", cfg.getSocketPath(), "Empty = <project dir>/<project>.sock; temp path for temporary projects");

        // Copy URL button row
        g.gridy = 6; g.gridx = 0; g.weightx = 0;
        serverPanel.add(new JLabel(""), g);
        g.gridx = 1; g.weightx = 1.0; g.fill = GridBagConstraints.NONE;
        JButton copyUrlBtn = new JButton("Copy Server URL");
        copyUrlBtn.addActionListener(e -> {
            String proto = cfg.isTlsEnabled() ? "https" : "http";
            String url = proto + "://" + cfg.getBindAddress() + ":" + cfg.getPort() + "/";
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(url), null);
            statusLabel.setText("URL copied: " + url);
            statusLabel.setForeground(McpColors.GREEN);
        });
        serverPanel.add(copyUrlBtn, g);
        g.fill = GridBagConstraints.HORIZONTAL;
        allSections.add(serverPanel);

        // ── Limits ──
        JPanel limitsPanel = new JPanel(new GridBagLayout());
        limitsPanel.setBorder(new TitledBorder("Limits"));
        GridBagConstraints lg = grid();
        maxResponseBodyField = addValidatedRow(limitsPanel, lg, 0, "Max Response Body (bytes):", String.valueOf(cfg.getMaxResponseBodyBytes()), "1000-100000000");
        maxSitemapField = addValidatedRow(limitsPanel, lg, 1, "Max Sitemap Entries:", String.valueOf(cfg.getMaxSitemapEntries()), ">0");
        requestTimeoutField = addValidatedRow(limitsPanel, lg, 2, "Request Timeout (ms):", String.valueOf(cfg.getRequestTimeoutMs()), "1000-300000");
        cacheTtlField = addValidatedRow(limitsPanel, lg, 3, "Cache TTL (seconds):", String.valueOf(cfg.getCacheTtlSeconds()), "0-86400");
        rateLimitField = addValidatedRow(limitsPanel, lg, 4, "Rate Limit (per min):", String.valueOf(cfg.getRateLimitPerMinute()), "0-10000 (0=disabled)");
        maxConnPerIpField = addValidatedRow(limitsPanel, lg, 5, "Max Conns per IP:", String.valueOf(cfg.getMaxConnectionsPerIp()), "1-100");
        allSections.add(limitsPanel);

        // ── Features ──
        JPanel featuresPanel = new JPanel(new GridBagLayout());
        featuresPanel.setBorder(new TitledBorder("Features"));
        GridBagConstraints fg = grid();
        fg.gridwidth = 2;
        includeRequestBodyCheck = checkbox(featuresPanel, fg, 0, "Include request bodies in output", cfg.isIncludeRequestBody());
        includeResponseBodyCheck = checkbox(featuresPanel, fg, 1, "Include response bodies in output", cfg.isIncludeResponseBody());
        metricsEnabledCheck = checkbox(featuresPanel, fg, 2, "Enable metrics tracking", cfg.isMetricsEnabled());
        cacheEnabledCheck = checkbox(featuresPanel, fg, 3, "Enable request cache", cfg.isCacheEnabled());
        allSections.add(featuresPanel);

        // ── Operator approvals ──
        JPanel approvalsPanel = new JPanel(new GridBagLayout());
        approvalsPanel.setBorder(new TitledBorder("Operator approvals (Prompt mode / out-of-scope targets)"));
        GridBagConstraints agp = grid();
        approvalWaitField = addValidatedRow(approvalsPanel, agp, 0, "Wait for decision (seconds):",
                String.valueOf(cfg.getApprovalWaitSeconds()), "5-600");
        approvalWaitField.setToolTipText(McpColors.tooltip(
                "How long a gated tool call blocks for an operator decision. If nobody answers in "
                + "time the agent gets an APPROVAL_PENDING response and can retry."));
        approvalTtlField = addValidatedRow(approvalsPanel, agp, 1, "Pending TTL (seconds):",
                String.valueOf(cfg.getApprovalTtlSeconds()), "30-3600");
        approvalTtlField.setToolTipText(McpColors.tooltip(
                "How long an undecided approval stays queued for a retry before it expires and "
                + "is cancelled."));
        allSections.add(approvalsPanel);

        // ── Logging ──
        JPanel loggingPanel = new JPanel(new GridBagLayout());
        loggingPanel.setBorder(new TitledBorder("Logging"));
        GridBagConstraints logg = grid();
        logg.gridx = 0; logg.gridy = 0; logg.weightx = 0;
        loggingPanel.add(new JLabel("Log Level:"), logg);
        logg.gridx = 1; logg.weightx = 1.0;
        logLevelCombo = new JComboBox<>(new String[]{"DEBUG", "INFO", "WARN", "ERROR"});
        logLevelCombo.setSelectedItem(cfg.getLogLevel());
        loggingPanel.add(logLevelCombo, logg);

        loggingFilePathField = addRow(loggingPanel, logg, 1, "Log File Path:", cfg.getLoggingFilePath(), "Default: ~/burp-mcp-error.log");
        allSections.add(loggingPanel);

        // ── Security: Auth ──
        JPanel authPanel = new JPanel(new GridBagLayout());
        authPanel.setBorder(new TitledBorder("Security — Authentication"));
        GridBagConstraints ag = grid();
        ag.gridwidth = 2;
        authEnabledCheck = checkbox(authPanel, ag, 0, "Enable API authentication (Bearer token)", cfg.isAuthEnabled());

        ag.gridy = 1; ag.gridwidth = 1; ag.gridx = 0; ag.weightx = 0;
        authPanel.add(new JLabel("API Token:"), ag);
        ag.gridx = 1; ag.weightx = 1.0;
        JPanel tokenRow = new JPanel(new BorderLayout(5, 0));
        tokenField = new JPasswordField(cfg.getAuthToken(), 30);
        tokenField.setToolTipText("Bearer token — keep this secret");
        tokenRow.add(tokenField, BorderLayout.CENTER);
        JPanel tokenBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        JButton generateBtn = new JButton("Generate");
        generateBtn.addActionListener(e -> {
            tokenField.setText(McpConfig.generateToken());
            tokenField.setBackground(McpColors.GREEN);
            new Timer(800, ev -> tokenField.setBackground(UIManager.getColor("TextField.background"))).start();
        });
        showTokenCheck = new JCheckBox("Show");
        showTokenCheck.addActionListener(e -> tokenField.setEchoChar(showTokenCheck.isSelected() ? (char) 0 : '*'));
        tokenBtns.add(generateBtn);
        tokenBtns.add(showTokenCheck);
        JButton copyTokenBtn = new JButton("Copy Token");
        copyTokenBtn.addActionListener(e -> {
            String tok = new String(tokenField.getPassword());
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(tok), null);
            statusLabel.setText("Token copied to clipboard");
            statusLabel.setForeground(McpColors.GREEN);
        });
        tokenBtns.add(copyTokenBtn);
        tokenRow.add(tokenBtns, BorderLayout.EAST);
        authPanel.add(tokenRow, ag);
        allSections.add(authPanel);

        // ── Security: TLS ──
        JPanel tlsPanel = new JPanel(new GridBagLayout());
        tlsPanel.setBorder(new TitledBorder("Security — TLS (press Restart on Status to apply)"));
        GridBagConstraints tg = grid();
        tg.gridwidth = 2;
        tlsEnabledCheck = checkbox(tlsPanel, tg, 0, "Enable TLS/HTTPS", cfg.isTlsEnabled());
        tg.gridy = 1; tg.gridwidth = 1; tg.gridx = 0; tg.weightx = 0;
        tlsPanel.add(new JLabel("Certificate:"), tg);
        tg.gridx = 1; tg.weightx = 1.0;
        tlsModeCombo = new JComboBox<>(new String[]{"self_signed", "custom"});
        tlsModeCombo.setSelectedItem(cfg.getTlsMode());
        tlsPanel.add(tlsModeCombo, tg);
        tlsKeystorePathField = addRow(tlsPanel, tg, 2, "Keystore Path:", cfg.getTlsKeystorePath(), "PKCS12 keystore for custom mode");
        tlsKeystorePasswordField = new JPasswordField(cfg.getTlsKeystorePassword(), 25);
        tg.gridy = 3; tg.gridx = 0; tg.weightx = 0;
        tlsPanel.add(new JLabel("Keystore Password:"), tg);
        tg.gridx = 1; tg.weightx = 1.0;
        tlsPanel.add(tlsKeystorePasswordField, tg);
        allSections.add(tlsPanel);

        // ── Buttons ──
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        JButton saveBtn = new JButton("Save Settings");
        saveBtn.setMnemonic('S');
        saveBtn.addActionListener(e -> saveSettings());
        JButton defaultBtn = new JButton("Reset to Defaults");
        defaultBtn.setMnemonic('D');
        defaultBtn.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(this, "Reset all settings to defaults?", "Confirm", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                resetDefaults();
            }
        });
        JButton exportBtn = new JButton("Export Config");
        exportBtn.addActionListener(e -> exportConfig());
        JButton importBtn = new JButton("Import Config");
        importBtn.addActionListener(e -> importConfig());

        statusLabel = new JLabel("");
        statusLabel.setForeground(McpColors.GREEN);

        btnPanel.add(saveBtn);
        btnPanel.add(defaultBtn);
        btnPanel.add(exportBtn);
        btnPanel.add(importBtn);
        btnPanel.add(statusLabel);

        JScrollPane scroll = new JScrollPane(allSections);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        add(scroll, BorderLayout.CENTER);
        add(btnPanel, BorderLayout.SOUTH);
    }

    private void saveSettings() {
        try {
            McpConfig cfg = McpConfig.getInstance();

            cfg.setPort(parseInt(portField, 4444));
            cfg.setBindAddress(bindAddressField.getText().trim());
            cfg.setThreadPoolSize(parseInt(threadPoolField, 10));
            cfg.setMaxQueueSize(parseInt(maxQueueField, 100));
            cfg.setSocketEnabled(socketEnabledCheck.isSelected());
            cfg.setSocketPath(socketPathField.getText().trim());
            cfg.setMaxResponseBodyBytes(parseInt(maxResponseBodyField, 100_000));
            cfg.setMaxSitemapEntries(parseInt(maxSitemapField, 500));
            cfg.setRequestTimeoutMs(parseInt(requestTimeoutField, 30_000));
            cfg.setCacheTtlSeconds(parseInt(cacheTtlField, 300));
            cfg.setRateLimitPerMinute(parseInt(rateLimitField, 100));
            cfg.setMaxConnectionsPerIp(parseInt(maxConnPerIpField, 10));
            cfg.setIncludeRequestBody(includeRequestBodyCheck.isSelected());
            cfg.setIncludeResponseBody(includeResponseBodyCheck.isSelected());
            cfg.setMetricsEnabled(metricsEnabledCheck.isSelected());
            cfg.setCacheEnabled(cacheEnabledCheck.isSelected());
            cfg.setLogLevel((String) logLevelCombo.getSelectedItem());
            cfg.setLoggingFilePath(loggingFilePathField.getText().trim());
            cfg.setAuthEnabled(authEnabledCheck.isSelected());
            cfg.setAuthToken(new String(tokenField.getPassword()));
            cfg.setTlsEnabled(tlsEnabledCheck.isSelected());
            cfg.setTlsMode((String) tlsModeCombo.getSelectedItem());
            cfg.setTlsKeystorePath(tlsKeystorePathField.getText().trim());
            cfg.setTlsKeystorePassword(new String(tlsKeystorePasswordField.getPassword()));
            cfg.setApprovalWaitSeconds(parseInt(approvalWaitField, 30));
            cfg.setApprovalTtlSeconds(parseInt(approvalTtlField, 300));

            java.util.List<String> errors = cfg.validate();
            try {
                McpConfig.getRequestCache().setEnabled(cfg.isCacheEnabled());
            } catch (IllegalStateException ignored) {
                // Cache not initialized yet; startup wiring applies the flag.
            }
            if (!errors.isEmpty()) {
                statusLabel.setText("Saved with warnings: " + String.join("; ", errors));
                statusLabel.setForeground(McpColors.AMBER);
            } else {
                statusLabel.setText("Settings saved.");
                statusLabel.setForeground(McpColors.GREEN);
            }
        } catch (NumberFormatException ex) {
            statusLabel.setText("Error: invalid number format.");
            statusLabel.setForeground(McpColors.RED);
        }
    }

    private void resetDefaults() {
        portField.setText("4444");
        bindAddressField.setText("127.0.0.1");
        threadPoolField.setText("10");
        maxQueueField.setText("100");
        socketEnabledCheck.setSelected(true);
        socketPathField.setText("");
        maxResponseBodyField.setText("100000");
        maxSitemapField.setText("500");
        requestTimeoutField.setText("30000");
        cacheTtlField.setText("300");
        rateLimitField.setText("100");
        maxConnPerIpField.setText("10");
        includeRequestBodyCheck.setSelected(true);
        includeResponseBodyCheck.setSelected(true);
        metricsEnabledCheck.setSelected(true);
        cacheEnabledCheck.setSelected(true);
        logLevelCombo.setSelectedItem("INFO");
        loggingFilePathField.setText("");
        authEnabledCheck.setSelected(false);
        tokenField.setText("");
        tlsEnabledCheck.setSelected(false);
        tlsModeCombo.setSelectedItem("self_signed");
        tlsKeystorePathField.setText("");
        tlsKeystorePasswordField.setText("burpmcp");
        approvalWaitField.setText("30");
        approvalTtlField.setText("300");
        statusLabel.setText("Defaults loaded. Press Save to apply.");
        statusLabel.setForeground(McpColors.BLUE);
    }

    private void exportConfig() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("burp-mcp-config.json"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try (FileWriter fw = new FileWriter(chooser.getSelectedFile())) {
                McpConfig cfg = McpConfig.getInstance();
                java.util.List<String> lines = new java.util.ArrayList<>();
                lines.add(jsonLine("mcp_port", cfg.getPort()));
                lines.add(jsonLine("bind_address", cfg.getBindAddress()));
                lines.add(jsonLine("thread_pool_size", cfg.getThreadPoolSize()));
                lines.add(jsonLine("max_queue_size", cfg.getMaxQueueSize()));
                lines.add(jsonLine("socket_enabled", cfg.isSocketEnabled()));
                lines.add(jsonLine("socket_path", cfg.getSocketPath()));
                lines.add(jsonLine("max_response_body_bytes", cfg.getMaxResponseBodyBytes()));
                lines.add(jsonLine("max_sitemap_entries", cfg.getMaxSitemapEntries()));
                lines.add(jsonLine("request_timeout_ms", cfg.getRequestTimeoutMs()));
                lines.add(jsonLine("cache_ttl_seconds", cfg.getCacheTtlSeconds()));
                lines.add(jsonLine("rate_limit_per_minute", cfg.getRateLimitPerMinute()));
                lines.add(jsonLine("max_connections_per_ip", cfg.getMaxConnectionsPerIp()));
                lines.add(jsonLine("log_level", cfg.getLogLevel()));
                lines.add(jsonLine("logging_file_path", cfg.getLoggingFilePath()));
                lines.add(jsonLine("include_request_body", cfg.isIncludeRequestBody()));
                lines.add(jsonLine("include_response_body", cfg.isIncludeResponseBody()));
                lines.add(jsonLine("metrics_enabled", cfg.isMetricsEnabled()));
                lines.add(jsonLine("cache_enabled", cfg.isCacheEnabled()));
                lines.add(jsonLine("auth_enabled", cfg.isAuthEnabled()));
                lines.add(jsonLine("tls_enabled", cfg.isTlsEnabled()));
                lines.add(jsonLine("approval_wait_seconds", cfg.getApprovalWaitSeconds()));
                lines.add(jsonLine("approval_ttl_seconds", cfg.getApprovalTtlSeconds()));
                lines.add(jsonLine("tls_mode", cfg.getTlsMode()));
                lines.add(jsonLine("tls_keystore_path", cfg.getTlsKeystorePath()));
                // Secrets (auth_token, tls_keystore_password) are deliberately omitted.
                fw.write("{\n" + String.join(",\n", lines) + "\n}\n");
                statusLabel.setText("Config exported (secrets omitted).");
                statusLabel.setForeground(McpColors.GREEN);
            } catch (IOException ex) {
                statusLabel.setText("Export failed: " + ex.getMessage());
                statusLabel.setForeground(McpColors.RED);
            }
        }
    }

    private void importConfig() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                String json = Files.readString(Paths.get(chooser.getSelectedFile().toURI()));
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var node = mapper.readTree(json);
                if (node.has("mcp_port")) portField.setText(String.valueOf(node.get("mcp_port").asInt()));
                if (node.has("bind_address")) bindAddressField.setText(node.get("bind_address").asText());
                if (node.has("thread_pool_size")) threadPoolField.setText(String.valueOf(node.get("thread_pool_size").asInt()));
                if (node.has("max_queue_size")) maxQueueField.setText(String.valueOf(node.get("max_queue_size").asInt()));
                if (node.has("socket_enabled")) socketEnabledCheck.setSelected(node.get("socket_enabled").asBoolean());
                if (node.has("socket_path")) socketPathField.setText(node.get("socket_path").asText());
                if (node.has("max_response_body_bytes")) maxResponseBodyField.setText(String.valueOf(node.get("max_response_body_bytes").asInt()));
                if (node.has("max_sitemap_entries")) maxSitemapField.setText(String.valueOf(node.get("max_sitemap_entries").asInt()));
                if (node.has("request_timeout_ms")) requestTimeoutField.setText(String.valueOf(node.get("request_timeout_ms").asInt()));
                if (node.has("cache_ttl_seconds")) cacheTtlField.setText(String.valueOf(node.get("cache_ttl_seconds").asInt()));
                if (node.has("rate_limit_per_minute")) rateLimitField.setText(String.valueOf(node.get("rate_limit_per_minute").asInt()));
                if (node.has("max_connections_per_ip")) maxConnPerIpField.setText(String.valueOf(node.get("max_connections_per_ip").asInt()));
                if (node.has("log_level")) logLevelCombo.setSelectedItem(node.get("log_level").asText());
                if (node.has("logging_file_path")) loggingFilePathField.setText(node.get("logging_file_path").asText());
                if (node.has("include_request_body")) includeRequestBodyCheck.setSelected(node.get("include_request_body").asBoolean());
                if (node.has("include_response_body")) includeResponseBodyCheck.setSelected(node.get("include_response_body").asBoolean());
                if (node.has("metrics_enabled")) metricsEnabledCheck.setSelected(node.get("metrics_enabled").asBoolean());
                if (node.has("cache_enabled")) cacheEnabledCheck.setSelected(node.get("cache_enabled").asBoolean());
                if (node.has("auth_enabled")) authEnabledCheck.setSelected(node.get("auth_enabled").asBoolean());
                if (node.has("tls_enabled")) tlsEnabledCheck.setSelected(node.get("tls_enabled").asBoolean());
                if (node.has("approval_wait_seconds")) approvalWaitField.setText(String.valueOf(node.get("approval_wait_seconds").asInt()));
                if (node.has("approval_ttl_seconds")) approvalTtlField.setText(String.valueOf(node.get("approval_ttl_seconds").asInt()));
                if (node.has("tls_mode")) tlsModeCombo.setSelectedItem(node.get("tls_mode").asText());
                statusLabel.setText("Config imported. Press Save to apply.");
                statusLabel.setForeground(McpColors.BLUE);
            } catch (Exception ex) {
                statusLabel.setText("Import failed: " + ex.getMessage());
                statusLabel.setForeground(McpColors.RED);
            }
        }
    }

    private static void putJson(StringBuilder sb, String key, String val) { sb.append("  \"").append(key).append("\": \"").append(val).append("\",\n"); }
    private static void putJson(StringBuilder sb, String key, int val) { sb.append("  \"").append(key).append("\": ").append(val).append(",\n"); }
    private static void putJson(StringBuilder sb, String key, boolean val) { sb.append("  \"").append(key).append("\": ").append(val).append(",\n"); }

    private static String jsonLine(String key, String val) {
        String safe = val == null ? "" : val.replace("\\", "\\\\").replace("\"", "\\\"");
        return "  \"" + key + "\": \"" + safe + "\"";
    }

    private static String jsonLine(String key, int val) {
        return "  \"" + key + "\": " + val;
    }

    private static String jsonLine(String key, boolean val) {
        return "  \"" + key + "\": " + val;
    }

    private JTextField addRow(JPanel p, GridBagConstraints gbc, int row, String label, String value, String hint) {
        gbc.gridy = row; gbc.gridx = 0; gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        JLabel lbl = new JLabel(label);
        lbl.setFont(McpColors.LABEL_FONT);
        lbl.setHorizontalAlignment(SwingConstants.LEFT);
        p.add(lbl, gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JTextField f = new JTextField(value, 20);
        f.setHorizontalAlignment(SwingConstants.LEFT);
        f.setToolTipText(hint);
        p.add(f, gbc);
        return f;
    }

    private JTextField addValidatedRow(JPanel p, GridBagConstraints gbc, int row, String label, String value, String hint) {
        JTextField f = addRow(p, gbc, row, label, value, hint);
        f.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void validate() {
                try {
                    int v = Integer.parseInt(f.getText().trim());
                    boolean ok = switch (hint) {
                        case "1-65535" -> v >= 1 && v <= 65535;
                        case "1-50" -> v >= 1 && v <= 50;
                        case "1-1000" -> v >= 1 && v <= 1000;
                        case "1000-100000000" -> v >= 1000 && v <= 100_000_000;
                        case ">0" -> v > 0;
                        case "1000-300000" -> v >= 1000 && v <= 300_000;
                        case "0-86400" -> v >= 0 && v <= 86400;
                        case "0-10000 (0=disabled)" -> v >= 0 && v <= 10000;
                        case "1-100" -> v >= 1 && v <= 100;
                        case "5-600" -> v >= 5 && v <= 600;
                        case "30-3600" -> v >= 30 && v <= 3600;
                        default -> true;
                    };
                    f.setBorder(ok ? UIManager.getBorder("TextField.border")
                            : BorderFactory.createLineBorder(McpColors.RED, 1));
                } catch (NumberFormatException ex) {
                    f.setBorder(BorderFactory.createLineBorder(McpColors.RED, 1));
                }
            }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { validate(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { validate(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { validate(); }
        });
        return f;
    }

    private JCheckBox checkbox(JPanel p, GridBagConstraints gbc, int row, String text, boolean selected) {
        gbc.gridy = row;
        JCheckBox cb = new JCheckBox(text, selected);
        cb.setToolTipText(text);
        p.add(cb, gbc);
        return cb;
    }

    private GridBagConstraints grid() {
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 6, 3, 6);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        return g;
    }

    private int parseInt(JTextField f, int d) { try { return Integer.parseInt(f.getText().trim()); } catch (NumberFormatException e) { return d; } }
}
