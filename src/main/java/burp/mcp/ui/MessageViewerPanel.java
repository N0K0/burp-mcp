package burp.mcp.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import burp.api.montoya.http.message.responses.HttpResponse;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Message Viewer — browse proxy history via a searchable dropdown,
 * navigate with prev/next, inspect request/response in split pane.
 */
public class MessageViewerPanel extends JPanel {

    private final MontoyaApi api;

    // Selection
    private final JComboBox<String> entryCombo;
    private final DefaultComboBoxModel<String> comboModel;
    private final JComboBox<String> sourceCombo;
    private final JLabel navLabel;

    // Cached entries: list of {id, label} pairs
    private final List<ProxyEntry> proxyEntries = new ArrayList<>();
    private final List<SitemapEntry> sitemapEntries = new ArrayList<>();
    private int currentIndex = -1;

    // Viewer
    private final JTextPane requestPane;
    private final JTextPane responsePane;
    private final JLabel statusLabel;

    private HttpRequest currentRequest;
    private HttpResponse currentResponse;

    private static final Color C_METHOD = new Color(0, 100, 200);
    private static final Color C_URL = new Color(0, 80, 160);
    private static final Color C_HDR_NAME = new Color(100, 60, 0);
    private static final Color C_HDR_VAL = new Color(60, 60, 60);
    private static final Color C_STATUS = new Color(0, 130, 0);
    private static final Color C_BODY = new Color(30, 30, 30);
    private static final Color C_SEP = new Color(180, 180, 180);

    public MessageViewerPanel(MontoyaApi api) {
        this.api = api;

        setLayout(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(10, 10, 10, 10));

        // ── Top bar ──
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        topBar.setBorder(new TitledBorder("Browse & Load"));

        sourceCombo = new JComboBox<>(new String[]{"Proxy History", "Sitemap (URL)", "WebSocket Messages"});
        sourceCombo.addActionListener(e -> onSourceChange());
        topBar.add(new JLabel("Source:"));
        topBar.add(sourceCombo);

        comboModel = new DefaultComboBoxModel<>();
        entryCombo = new JComboBox<>(comboModel);
        entryCombo.setEditable(true);
        entryCombo.setPreferredSize(new Dimension(420, 24));
        entryCombo.setToolTipText("Type to filter, or pick from dropdown. Press Enter to load.");
        entryCombo.getEditor().getEditorComponent().addKeyListener(new java.awt.event.KeyAdapter() {
            @Override public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER && comboModel.getSize() > 0) {
                    loadSelected();
                }
            }
        });
        topBar.add(entryCombo);

        JButton loadBtn = new JButton("Load");
        loadBtn.addActionListener(e -> loadSelected());
        topBar.add(loadBtn);

        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(e -> refreshEntries());
        topBar.add(refreshBtn);

        topBar.add(new JSeparator(SwingConstants.VERTICAL));

        JButton prevBtn = new JButton("\u25C0");
        prevBtn.setToolTipText("Previous entry");
        prevBtn.addActionListener(e -> navigate(-1));
        JButton nextBtn = new JButton("\u25B6");
        nextBtn.setToolTipText("Next entry");
        nextBtn.addActionListener(e -> navigate(+1));
        navLabel = new JLabel("-/-");
        topBar.add(prevBtn);
        topBar.add(navLabel);
        topBar.add(nextBtn);

        topBar.add(new JSeparator(SwingConstants.VERTICAL));

        JButton copyReqBtn = new JButton("Copy Req");
        copyReqBtn.addActionListener(e -> copyRequest());
        JButton copyRespBtn = new JButton("Copy Resp");
        copyRespBtn.addActionListener(e -> copyResponse());
        topBar.add(copyReqBtn);
        topBar.add(copyRespBtn);

        statusLabel = new JLabel("Click Refresh to populate the dropdown");
        statusLabel.setForeground(McpColors.GRAY);
        topBar.add(statusLabel);

        add(topBar, BorderLayout.NORTH);

        // ── Request / Response split ──
        JPanel reqPanel = new JPanel(new BorderLayout());
        reqPanel.setBorder(new TitledBorder("Request"));
        requestPane = createPane();
        reqPanel.add(new JScrollPane(requestPane), BorderLayout.CENTER);
        JPanel reqBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        JButton toRep = new JButton("Send to Repeater");
        toRep.addActionListener(e -> sendToRepeater());
        JButton toInt = new JButton("Send to Intruder");
        toInt.addActionListener(e -> sendToIntruder());
        reqBtns.add(toRep); reqBtns.add(toInt);
        reqPanel.add(reqBtns, BorderLayout.SOUTH);

        JPanel respPanel = new JPanel(new BorderLayout());
        respPanel.setBorder(new TitledBorder("Response"));
        responsePane = createPane();
        respPanel.add(new JScrollPane(responsePane), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, reqPanel, respPanel);
        split.setResizeWeight(0.5);
        split.setDividerLocation(300);
        add(split, BorderLayout.CENTER);

        refreshEntries();
    }

    private JTextPane createPane() {
        JTextPane p = new JTextPane();
        p.setEditable(false);
        p.setFont(McpColors.MONO_FONT);
        p.setBackground(new Color(250, 250, 255));
        return p;
    }

    // ── Refresh ──

    private void refreshEntries() {
        String src = (String) sourceCombo.getSelectedItem();
        proxyEntries.clear();
        sitemapEntries.clear();
        comboModel.removeAllElements();

        if ("Proxy History".equals(src)) {
            int count = 0;
            for (var e : api.proxy().history()) {
                if (count++ >= 2000) break;
                int sc = e.response() != null ? e.response().statusCode() : 0;
                String label = "[" + sc + "] " + e.finalRequest().method() + " " + shortUrl(e.finalRequest().url(), 80);
                proxyEntries.add(new ProxyEntry(e.id(), label));
                comboModel.addElement(e.id() + "  " + label);
            }
            statusLabel.setText(count + " proxy entries");
        } else if ("Sitemap (URL)".equals(src)) {
            int count = 0;
            for (var e : api.siteMap().requestResponses()) {
                if (count++ >= 2000) break;
                int sc = e.response() != null ? e.response().statusCode() : 0;
                String url = e.request() != null ? e.request().url() : "?";
                String method = e.request() != null ? e.request().method() : "?";
                String label = "[" + sc + "] " + method + " " + shortUrl(url, 80);
                sitemapEntries.add(new SitemapEntry(url, label));
                comboModel.addElement(count + "  " + label);
            }
            statusLabel.setText(count + " sitemap entries");
        } else {
            var msgs = api.proxy().webSocketHistory();
            for (int i = 0; i < msgs.size() && i < 2000; i++) {
                var m = msgs.get(i);
                String label = m.direction().name() + " | " + m.payload().length() + "b | " + m.time();
                proxyEntries.add(new ProxyEntry(i, label)); // reuse ProxyEntry for WS index
                comboModel.addElement(i + "  " + label);
            }
            statusLabel.setText(msgs.size() + " WS messages");
        }
        statusLabel.setForeground(McpColors.GREEN);
        currentIndex = -1;
        navLabel.setText("-/" + comboModel.getSize());
    }

    private void onSourceChange() {
        refreshEntries();
        clearPanes();
    }

    // ── Load ──

    private void loadSelected() {
        int idx = entryCombo.getSelectedIndex();
        if (idx < 0) return;
        currentIndex = idx;
        navLabel.setText((idx + 1) + "/" + comboModel.getSize());

        String src = (String) sourceCombo.getSelectedItem();
        if ("Proxy History".equals(src) && idx < proxyEntries.size()) {
            loadProxy(proxyEntries.get(idx).id);
        } else if ("Sitemap (URL)".equals(src) && idx < sitemapEntries.size()) {
            loadSitemap(sitemapEntries.get(idx).url);
        } else if (idx < proxyEntries.size()) {
            loadWebSocket(proxyEntries.get(idx).id);
        }
    }

    private void navigate(int delta) {
        if (comboModel.getSize() == 0) return;
        int idx = currentIndex + delta;
        if (idx < 0) idx = comboModel.getSize() - 1;
        if (idx >= comboModel.getSize()) idx = 0;
        entryCombo.setSelectedIndex(idx);
        loadSelected();
    }

    private void loadProxy(int id) {
        for (ProxyHttpRequestResponse e : api.proxy().history()) {
            if (e.id() == id) {
                currentRequest = e.finalRequest();
                currentResponse = e.response();
                renderRequest(currentRequest);
                renderResponse(currentResponse);
                statusLabel.setText("#" + id + " — " + currentRequest.method() + " " + shortUrl(currentRequest.url(), 60));
                statusLabel.setForeground(McpColors.GREEN);
                return;
            }
        }
        statusLabel.setText("Not found: #" + id);
        statusLabel.setForeground(McpColors.RED);
    }

    private void loadSitemap(String url) {
        for (var e : api.siteMap().requestResponses()) {
            String eu = e.request() != null ? e.request().url() : null;
            if (url.equals(eu)) {
                currentRequest = e.request();
                currentResponse = e.response();
                renderRequest(currentRequest);
                renderResponse(currentResponse);
                statusLabel.setText(shortUrl(url, 60));
                statusLabel.setForeground(McpColors.GREEN);
                return;
            }
        }
        statusLabel.setText("Not found: " + url);
        statusLabel.setForeground(McpColors.RED);
    }

    private void loadWebSocket(int index) {
        var msgs = api.proxy().webSocketHistory();
        if (index < 0 || index >= msgs.size()) { statusLabel.setText("WS not found: " + index); return; }
        var msg = msgs.get(index);
        currentRequest = null; currentResponse = null;
        renderRequest(msg.upgradeRequest());
        byte[] payload = msg.payload().getBytes();
        String text = burp.mcp.util.ByteArrayConverter.bytesToString(payload);
        StyledDocument doc = responsePane.getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
            Style dir = bold(doc, McpColors.GREEN);
            Style info = style(doc, McpColors.GRAY);
            Style body = style(doc, C_BODY);
            doc.insertString(doc.getLength(), msg.direction().name() + " ", dir);
            doc.insertString(doc.getLength(), msg.time() + " | " + payload.length + " bytes\n\n", info);
            try {
                var node = burp.mcp.util.McpJson.mapper().readTree(text);
                text = burp.mcp.util.McpJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(node);
            } catch (Exception ignored) {}
            doc.insertString(doc.getLength(), text, body);
        } catch (BadLocationException ignored) {}
        statusLabel.setText("WS #" + index + " — " + msg.direction().name() + " — " + payload.length + " bytes");
        statusLabel.setForeground(McpColors.GREEN);
    }

    // ── Render ──

    private void renderRequest(HttpRequest req) {
        if (req == null) { requestPane.setText("(no request)"); return; }
        StyledDocument d = requestPane.getStyledDocument();
        try {
            d.remove(0, d.getLength());
            Style m = bold(d, C_METHOD); Style u = style(d, C_URL);
            Style hn = bold(d, C_HDR_NAME); Style hv = style(d, C_HDR_VAL);
            Style b = style(d, C_BODY); Style s = italic(d, C_SEP);
            d.insertString(d.getLength(), req.method() + " ", m);
            d.insertString(d.getLength(), req.url() + " ", u);
            d.insertString(d.getLength(), req.httpVersion() + "\n", s);
            for (var h : req.headers()) {
                d.insertString(d.getLength(), h.name() + ": ", hn);
                d.insertString(d.getLength(), h.value() + "\n", hv);
            }
            d.insertString(d.getLength(), "\n", s);
            if (req.body() != null && req.body().length() > 0)
                d.insertString(d.getLength(), formatBody(
                        new String(req.body().getBytes(), StandardCharsets.UTF_8),
                        req.contentType() != null ? req.contentType().name() : ""), b);
        } catch (BadLocationException ignored) {}
    }

    private void renderResponse(HttpResponse resp) {
        if (resp == null) { responsePane.setText("(no response)"); return; }
        StyledDocument d = responsePane.getStyledDocument();
        try {
            d.remove(0, d.getLength());
            boolean err = resp.statusCode() >= 400;
            Style st = bold(d, err ? McpColors.RED : C_STATUS);
            Style hn = bold(d, C_HDR_NAME); Style hv = style(d, C_HDR_VAL);
            Style b = style(d, C_BODY); Style s = italic(d, C_SEP);
            d.insertString(d.getLength(), resp.httpVersion() + " ", s);
            d.insertString(d.getLength(), resp.statusCode() + " " + resp.reasonPhrase() + "\n", st);
            for (var h : resp.headers()) {
                d.insertString(d.getLength(), h.name() + ": ", hn);
                d.insertString(d.getLength(), h.value() + "\n", hv);
            }
            d.insertString(d.getLength(), "\n", s);
            if (resp.body() != null && resp.body().length() > 0)
                d.insertString(d.getLength(), formatBody(
                        new String(resp.body().getBytes(), StandardCharsets.UTF_8),
                        resp.mimeType() != null ? resp.mimeType().name() : ""), b);
        } catch (BadLocationException ignored) {}
    }

    private Style bold(StyledDocument d, Color c) { Style s = d.addStyle(null, null); StyleConstants.setForeground(s, c); StyleConstants.setBold(s, true); return s; }
    private Style style(StyledDocument d, Color c) { Style s = d.addStyle(null, null); StyleConstants.setForeground(s, c); return s; }
    private Style italic(StyledDocument d, Color c) { Style s = d.addStyle(null, null); StyleConstants.setForeground(s, c); StyleConstants.setItalic(s, true); return s; }

    // ── Body formatting ──

    private String formatBody(String raw, String ct) {
        if (raw == null || raw.isEmpty()) return "";
        if (ct.contains("json") || raw.trim().startsWith("{") || raw.trim().startsWith("[")) {
            try { return burp.mcp.util.McpJson.mapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(burp.mcp.util.McpJson.mapper().readTree(raw)); } catch (Exception ignored) {}
        }
        if (ct.contains("xml") || ct.contains("html") || raw.trim().startsWith("<")) {
            try { return indentXml(raw); } catch (Exception ignored) {}
        }
        int np = 0;
        for (int i = 0; i < Math.min(raw.length(), 512); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') np++;
        }
        if (np > raw.length() / 4 && raw.length() > 0) return hexDump(raw.getBytes(StandardCharsets.ISO_8859_1), 256);
        return raw;
    }

    private String indentXml(String xml) {
        StringBuilder sb = new StringBuilder(); int indent = 0;
        for (String t : xml.split("(?=<)|(?<=>)")) {
            t = t.trim(); if (t.isEmpty()) continue;
            if (t.startsWith("</")) indent = Math.max(0, indent - 1);
            sb.append("  ".repeat(indent)).append(t).append("\n");
            if (t.startsWith("<") && !t.startsWith("</") && !t.startsWith("<?") && !t.endsWith("/>") && !t.contains("</")) indent++;
        }
        return sb.toString();
    }

    private String hexDump(byte[] bytes, int max) {
        StringBuilder sb = new StringBuilder("[Binary — hex dump]\n");
        int len = Math.min(bytes.length, max);
        HexFormat hf = HexFormat.of().withDelimiter(" ");
        for (int i = 0; i < len; i += 16) {
            int end = Math.min(i + 16, len);
            sb.append(String.format("%04x  %-48s  ", i, hf.formatHex(java.util.Arrays.copyOfRange(bytes, i, end))));
            for (int j = i; j < end; j++) sb.append(bytes[j] >= 0x20 && bytes[j] < 0x7f ? (char) bytes[j] : '.');
            sb.append("\n");
        }
        if (bytes.length > max) sb.append("... (").append(bytes.length - max).append(" more bytes)\n");
        return sb.toString();
    }

    // ── Actions ──

    private void sendToRepeater() {
        if (currentRequest == null) return;
        try { api.repeater().sendToRepeater(currentRequest, ""); statusLabel.setText("Sent to Repeater"); statusLabel.setForeground(McpColors.GREEN); }
        catch (Exception e) { statusLabel.setText("Failed: " + e.getMessage()); statusLabel.setForeground(McpColors.RED); }
    }

    private void sendToIntruder() {
        if (currentRequest == null) return;
        try { api.intruder().sendToIntruder(HttpRequest.httpRequest(buildRaw(currentRequest)), ""); statusLabel.setText("Sent to Intruder"); statusLabel.setForeground(McpColors.GREEN); }
        catch (Exception e) { statusLabel.setText("Failed: " + e.getMessage()); statusLabel.setForeground(McpColors.RED); }
    }

    private String buildRaw(HttpRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append(req.method()).append(" ").append(req.path()).append(" ").append(req.httpVersion()).append("\r\n");
        for (var h : req.headers()) sb.append(h.name()).append(": ").append(h.value()).append("\r\n");
        sb.append("\r\n");
        if (req.body() != null && req.body().length() > 0) sb.append(new String(req.body().getBytes(), StandardCharsets.UTF_8));
        return sb.toString();
    }

    private void copyRequest() {
        if (currentRequest == null) return;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(buildRaw(currentRequest)), null);
    }

    private void copyResponse() {
        if (currentResponse == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(currentResponse.httpVersion()).append(" ").append(currentResponse.statusCode()).append(" ").append(currentResponse.reasonPhrase()).append("\r\n");
        for (var h : currentResponse.headers()) sb.append(h.name()).append(": ").append(h.value()).append("\r\n");
        sb.append("\r\n");
        if (currentResponse.body() != null && currentResponse.body().length() > 0) sb.append(new String(currentResponse.body().getBytes(), StandardCharsets.UTF_8));
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString()), null);
    }

    private void clearPanes() { currentRequest = null; currentResponse = null; requestPane.setText(""); responsePane.setText(""); }
    private static String shortUrl(String u, int max) { return u == null ? "" : u.length() > max ? u.substring(0, max - 3) + "..." : u; }

    private record ProxyEntry(int id, String label) {}
    private record SitemapEntry(String url, String label) {}
}
