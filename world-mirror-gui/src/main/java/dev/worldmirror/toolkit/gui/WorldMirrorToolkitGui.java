package dev.worldmirror.toolkit.gui;

import java.awt.BorderLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Placeholder GUI module.
 *
 * <p>The module intentionally depends on the same core libraries as the CLI so future GUI work can
 * call the stable services directly without booting Minecraft.</p>
 */
public final class WorldMirrorToolkitGui {
    private WorldMirrorToolkitGui() {}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(WorldMirrorToolkitGui::show);
    }

    private static void show() {
        JFrame frame = new JFrame("world-mirror-toolkit");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(720, 240);
        JPanel panel = new JPanel(new BorderLayout(16, 16));
        panel.add(new JLabel("world-mirror-toolkit GUI placeholder. Use the CLI for now."), BorderLayout.CENTER);
        JButton close = new JButton("Close");
        close.addActionListener(event -> frame.dispose());
        panel.add(close, BorderLayout.SOUTH);
        frame.setContentPane(panel);
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
    }
}
