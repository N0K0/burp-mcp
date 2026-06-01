package burp.mcp.ui;

import burp.api.montoya.MontoyaApi;
import burp.mcp.tool.McpToolRegistry;
import burp.mcp.tool.Tool;
import burp.mcp.util.McpConfig;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Panel for test-invoking MCP tools with custom JSON arguments.
 * Split-view: tool list + schema + args on left, result viewer on right.
 * History, copy-as-curl, format, share, and error treatment.
 */
public class ToolTesterPanel extends JPanel {

    private final McpToolRegistry registry;
    private final JComboBox<String> toolSelector;
    private final JTextArea descArea;
    private final JTextArea schemaArea;
    private final JTextArea argsArea;
    private final JTextArea resultArea;
    private final JLabel statusLabel;
    private final JComboBox<String> historyCombo;

    private final List<HistoryEntry> history = new ArrayList<>();
    private static final int MAX_HISTORY = 20;

    public ToolTesterPanel(MontoyaApi api, McpToolRegistry registry) {
        this.registry = registry;

        setLayout(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(10, 10, 10, 10));

        // ── LEFT SIDE: Tool selector, schema, args ──
        JPanel leftPanel = new JPanel(new BorderLayout(5, 5));
        leftPanel.setBorder(new TitledBorder("Tool"));

        // Tool selector bar
        JPanel selectorBar = new JPanel(new BorderLayout(5, 0));
        List<String> toolNames = new ArrayList<>(registry.getToolNames());
        toolNames.sort(String::compareToIgnoreCase);
        toolSelector = new JComboBox<>(toolNames.toArray(new String[0]));
        toolSelector.addActionListener(e -> onToolSelected());
        selectorBar.add(toolSelector, BorderLayout.CENTER);
        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(e -> refreshToolList());
        selectorBar.add(refreshBtn, BorderLayout.EAST);
        leftPanel.add(selectorBar, BorderLayout.NORTH);

        // Description
        descArea = new JTextArea(2, 40);
        descArea.setEditable(false);
        descArea.setLineWrap(true);
        descArea.setWrapStyleWord(true);
        descArea.setFont(McpColors.LABEL_FONT);
        descArea.setBackground(new Color(250, 250, 255));
        descArea.setBorder(new EmptyBorder(4, 6, 4, 6));
        leftPanel.add(new JScrollPane(descArea), BorderLayout.CENTER);

        // Schema + args in tabs — Arguments FIRST
        JTabbedPane schemaTabs = new JTabbedPane();

        JPanel argsPanel = new JPanel(new BorderLayout(5, 5));
        argsArea = new JTextArea(10, 35);
        argsArea.setFont(McpColors.MONO_FONT);
        argsArea.setText("{ }");
        argsPanel.add(new JScrollPane(argsArea), BorderLayout.CENTER);

        // Button bar — same FlowLayout as result bar for matching spacing
        JPanel invokeBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 3));

        JPanel historyGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        historyGroup.add(new JLabel("Hist:"));
        historyCombo = new JComboBox<>(new String[]{"History..."});
        historyCombo.addActionListener(e -> {
            int idx = historyCombo.getSelectedIndex();
            if (idx > 0 && idx <= history.size()) {
                HistoryEntry he = history.get(idx - 1);
                argsArea.setText(he.args);
            }
        });
        historyCombo.setPreferredSize(new Dimension(140, historyCombo.getPreferredSize().height));
        historyGroup.add(historyCombo);
        invokeBar.add(historyGroup);

        JButton formatBtn = new JButton("Format");
        formatBtn.addActionListener(e -> formatArgs());
        invokeBar.add(formatBtn);

        JButton invokeBtn = new JButton("Invoke");
        invokeBtn.addActionListener(e -> invokeTool());
        invokeBar.add(invokeBtn);

        statusLabel = new JLabel("");
        invokeBar.add(statusLabel);
        argsPanel.add(invokeBar, BorderLayout.SOUTH);
        schemaTabs.addTab("Arguments", argsPanel);

        schemaArea = new JTextArea(10, 35);
        schemaArea.setEditable(false);
        schemaArea.setFont(McpColors.MONO_SMALL);
        schemaArea.setBackground(new Color(240, 240, 245));
        schemaTabs.addTab("Schema", new JScrollPane(schemaArea));

        leftPanel.add(schemaTabs, BorderLayout.SOUTH);

        // ── RIGHT SIDE: Result viewer ──
        JPanel rightPanel = new JPanel(new BorderLayout(5, 5));
        rightPanel.setBorder(new TitledBorder("Result"));
        resultArea = new JTextArea(20, 40);
        resultArea.setEditable(false);
        resultArea.setFont(McpColors.MONO_FONT);
        resultArea.setBackground(new Color(245, 245, 245));
        resultArea.setForeground(new Color(30, 30, 30));
        JScrollPane resultScroll = new JScrollPane(resultArea);

        JPanel resultBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 3));
        JButton copyResultBtn = new JButton("Copy");
        copyResultBtn.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(resultArea.getText()), null));
        JButton copyCurlBtn = new JButton("Copy cURL");
        copyCurlBtn.addActionListener(e -> copyAsCurl());
        JButton shareBtn = new JButton("Share");
        shareBtn.addActionListener(e -> copyAsMarkdown());
        JButton clearResultBtn = new JButton("Clear");
        clearResultBtn.addActionListener(e -> resultArea.setText(""));
        resultBar.add(copyResultBtn);
        resultBar.add(copyCurlBtn);
        resultBar.add(shareBtn);
        resultBar.add(clearResultBtn);
        rightPanel.add(resultScroll, BorderLayout.CENTER);
        rightPanel.add(resultBar, BorderLayout.SOUTH);

        // ── Layout: left | right split ──
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        split.setResizeWeight(0.5);
        split.setDividerLocation(450);

        add(split, BorderLayout.CENTER);

        onToolSelected();
    }

    private void onToolSelected() {
        String name = (String) toolSelector.getSelectedItem();
        if (name == null) return;
        Tool tool = registry.getTool(name);
        if (tool == null) { descArea.setText("(not found)"); return; }

        descArea.setText(tool.definition().description());
        try {
            String pretty = McpJson.mapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(tool.inputSchema());
            schemaArea.setText(pretty);
        } catch (Exception e) {
            schemaArea.setText("Error: " + e.getMessage());
        }
        // Generate example if args empty
        String current = argsArea.getText().trim();
        if (current.isEmpty() || "{}".equals(current) || "{ }".equals(current)) {
            try { argsArea.setText(McpJson.generateExampleJson(tool.inputSchema())); }
            catch (Exception ex) { argsArea.setText("{ }"); }
        }
    }

    private void formatArgs() {
        try {
            ObjectNode parsed = (ObjectNode) McpJson.mapper().readTree(argsArea.getText());
            argsArea.setText(McpJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(parsed));
        } catch (Exception e) {
            statusLabel.setText("Invalid JSON");
            statusLabel.setForeground(McpColors.RED);
        }
    }

    private void refreshToolList() {
        String prev = (String) toolSelector.getSelectedItem();
        List<String> names = new ArrayList<>(registry.getToolNames());
        names.sort(String::compareToIgnoreCase);
        toolSelector.setModel(new DefaultComboBoxModel<>(names.toArray(new String[0])));
        if (prev != null && names.contains(prev)) toolSelector.setSelectedItem(prev);
        statusLabel.setText(names.size() + " tools");
        statusLabel.setForeground(McpColors.GREEN);
    }

    private void invokeTool() {
        String toolName = (String) toolSelector.getSelectedItem();
        if (toolName == null || toolName.isEmpty()) return;
        String argsText = argsArea.getText().trim();
        Map<String, Object> args;
        try {
            if (argsText.isEmpty() || "{}".equals(argsText) || "{ }".equals(argsText)) {
                args = java.util.Collections.emptyMap();
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = McpJson.mapper().readValue(argsText, Map.class);
                args = parsed;
            }
        } catch (Exception e) {
            statusLabel.setText("Invalid JSON: " + e.getMessage());
            statusLabel.setForeground(McpColors.RED);
            resultArea.setBorder(BorderFactory.createLineBorder(McpColors.RED, 2));
            return;
        }
        statusLabel.setText("Invoking...");
        statusLabel.setForeground(McpColors.BLUE);
        resultArea.setBorder(UIManager.getBorder("TextArea.border"));

        long start = System.currentTimeMillis();
        try {
            Object result = registry.callTool(toolName, args);
            long elapsed = System.currentTimeMillis() - start;
            String resultJson = McpJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(result);
            resultArea.setText(resultJson);
            statusLabel.setText("OK — " + elapsed + "ms");
            statusLabel.setForeground(McpColors.GREEN);
            resultArea.setBorder(BorderFactory.createLineBorder(McpColors.GREEN, 1));
            // Add to history
            history.add(0, new HistoryEntry(toolName, argsText));
            if (history.size() > MAX_HISTORY) history.remove(history.size() - 1);
            rebuildHistory();
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            String msg = e.getMessage();
            int code = -32603;
            if (e instanceof burp.mcp.util.McpError me) code = me.getCode();
            resultArea.setText("ERROR [" + code + "]: " + msg + "\n\nStack trace:\n" +
                    java.util.Arrays.stream(e.getStackTrace()).limit(15)
                            .map(StackTraceElement::toString).collect(Collectors.joining("\n")));
            statusLabel.setText("ERROR [" + code + "] — " + elapsed + "ms");
            statusLabel.setForeground(McpColors.RED);
            resultArea.setBorder(BorderFactory.createLineBorder(McpColors.RED, 2));
        }
    }

    private void rebuildHistory() {
        historyCombo.removeAllItems();
        historyCombo.addItem("History...");
        for (HistoryEntry he : history) historyCombo.addItem(he.label());
    }

    private void copyAsCurl() {
        String name = (String) toolSelector.getSelectedItem();
        String args = argsArea.getText().trim();
        McpConfig cfg = McpConfig.getInstance();
        String url = "http://127.0.0.1:" + cfg.getPort() + "/";
        String curl = "curl -s -X POST -H 'Content-Type: application/json' \\\n"
                + "  -d '{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + name + "\",\"arguments\":" + args + "},\"id\":\"1\"}' \\\n"
                + "  " + url;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(curl), null);
    }

    private void copyAsMarkdown() {
        String name = (String) toolSelector.getSelectedItem();
        String args = argsArea.getText().trim();
        String result = resultArea.getText();
        String md = "### Tool: `" + name + "`\n\n**Arguments:**\n```json\n" + args
                + "\n```\n\n**Result:**\n```json\n" + result + "\n```";
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(md), null);
    }

    private record HistoryEntry(String tool, String args) {
        String label() { return tool + " — " + (args.length() > 60 ? args.substring(0, 60) + "..." : args); }
    }
}
