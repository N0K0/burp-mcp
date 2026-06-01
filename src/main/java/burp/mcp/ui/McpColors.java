package burp.mcp.ui;

import java.awt.*;

/**
 * Shared color palette for consistent Burp MCP UI styling.
 */
public final class McpColors {

    public static final Color DARK_BG = new Color(43, 43, 43);
    public static final Color LIGHT_TEXT = new Color(187, 187, 187);
    public static final Color GREEN = new Color(76, 175, 80);
    public static final Color AMBER = new Color(255, 193, 7);
    public static final Color RED = new Color(244, 67, 54);
    public static final Color GRAY = new Color(120, 120, 120);
    public static final Color DARKER_BG = new Color(30, 30, 30);
    public static final Color BLUE = new Color(33, 150, 243);

    public static final Font LABEL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    public static final Font MONO_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    public static final Font MONO_SMALL = new Font(Font.MONOSPACED, Font.PLAIN, 11);
    public static final Font BOLD_LABEL = new Font(Font.SANS_SERIF, Font.BOLD, 12);

    // Tooltip helpers
    public static String tooltip(String text) {
        return "<html><body width='300'>" + text + "</body></html>";
    }

    private McpColors() {}
}
