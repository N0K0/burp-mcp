package burp.mcp.ui;

import javax.swing.*;
import java.awt.*;

/**
 * Minimal wrapper panel that catches all exceptions during Swing painting/layout.
 * Reveals the exact source of AWT-EventQueue NPEs.
 */
public class SafePanel extends JPanel {

    private final String name;

    public SafePanel(String name, java.util.function.Supplier<JComponent> factory) {
        this.name = name;
        setLayout(new BorderLayout());
        try {
            JComponent child = factory.get();
            if (child != null) {
                add(child, BorderLayout.CENTER);
            }
        } catch (Exception e) {
            burp.mcp.util.ErrorLogger.log("UI:" + name + "/construct", e);
            add(new JLabel("[Error loading " + name + ": " + e.toString() + "]"), BorderLayout.CENTER);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        try {
            super.paintComponent(g);
        } catch (Exception e) {
            burp.mcp.util.ErrorLogger.log("UI:" + name + "/pasent", e);
        }
    }

    @Override
    public void paint(Graphics g) {
        try {
            super.paint(g);
        } catch (Exception e) {
            burp.mcp.util.ErrorLogger.log("UI:" + name + "/paint", e);
        }
    }

    @Override
    public void validate() {
        try {
            super.validate();
        } catch (Exception e) {
            burp.mcp.util.ErrorLogger.log("UI:" + name + "/validate", e);
        }
    }

    @Override
    public void doLayout() {
        try {
            super.doLayout();
        } catch (Exception e) {
            burp.mcp.util.ErrorLogger.log("UI:" + name + "/doLayout", e);
        }
    }
}
