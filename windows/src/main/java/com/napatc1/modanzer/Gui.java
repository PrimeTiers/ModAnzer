package com.napatc1.modanzer;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/** The ModAnzer window, in PrimeTiers colours. */
final class Gui extends JFrame {
    private static final String[][] MODES = {
        { "mods", "Mod Analyzer", "Check every mods folder for cheats" },
        { "injection", "Injection Checker", "Check the running game and watch it live" },
        { "system", "System Check", "Deleted files, cleared logs, USB, tools" },
        { "full", "Full Screenshare", "All three, one after another" } };

    // Which launcher the Mod Analyzer looks in. "other" asks for a folder.
    private static final String[][] LAUNCHERS = {
        { "all", "All launchers" }, { "Minecraft Launcher", "Minecraft Launcher" }, { "CurseForge", "CurseForge" },
        { "Modrinth", "Modrinth App" }, { "Prism", "Prism / MultiMC" }, { "Lunar", "Lunar Client" }, { "other", "Other folder..." } };

    private static final String HINT_BEFORE = "When the scan ends, press Get code and give the code to staff.";
    private static final String HINT_READY = "Scan done. Press Get code to send the results and get a code for staff.";

    private final Content content = new Content();
    private final PixelButton[] modeButtons = new PixelButton[MODES.length];
    private final PixelButton[] launcherButtons = new PixelButton[LAUNCHERS.length];
    private final PixelButton start = new PixelButton("Start scan"), stop = new PixelButton("Stop live watch");
    private final PixelButton admin = new PixelButton("Restart as admin"), send = new PixelButton("Get code");
    private final StatBox[] stats = new StatBox[5];
    private final JTextPane output = new JTextPane();
    private final JScrollPane outScroll = new JScrollPane(output);
    private final JTextField code = new JTextField();
    private final Panel sendBar = new Panel(), outPanel = new Panel();
    private final Label titleA = new Label("Get scanned.", Theme.pixel(32), Theme.TEXT), titleB = new Label("Stay clean.", Theme.pixel(32), Theme.GOLD);
    private final Label blurb = new Label("Pick a check. ModAnzer only reads: it never changes, deletes or installs anything, and nothing is sent unless you press Get code.", Theme.sans(15), Theme.MUTED);
    private final Label launcherTitle = new Label("Launcher", Theme.pixel(14), Theme.MUTED);
    private final Label sendTitle = new Label("Send to staff", Theme.pixel(16), Theme.TEXT);
    private final Label sendHint = new Label(HINT_BEFORE, Theme.sans(12), Theme.MUTED);

    private final boolean isAdmin = Common.isAdmin();
    private final ConcurrentLinkedQueue<Object[]> queue = new ConcurrentLinkedQueue<>();
    private final Timer flush = new Timer(60, e -> flush());
    private final List<Integer> suspicious = new ArrayList<>();

    private String mode = "mods", launcher = "all", otherFolder;
    private volatile boolean running;
    private volatile String progress = "";
    private Options current;
    private Report report;
    private String reportJson;
    private java.awt.Image logo;

    Gui(String initialMode) {
        super("ModAnzer");
        try {
            URL u = Gui.class.getResource("/icon.png");
            if (u != null) { logo = javax.imageio.ImageIO.read(u); setIconImage(logo); }
        } catch (IOException ignored) { }
        if (initialMode != null) mode = initialMode;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        content.setBackground(Theme.BG);
        setContentPane(content);
        content.setPreferredSize(new Dimension(1120, 820));
        setMinimumSize(new Dimension(1000, 800));

        content.add(titleA);
        content.add(titleB);
        content.add(blurb);
        for (int i = 0; i < MODES.length; i++) {
            PixelButton b = new PixelButton(MODES[i][1]);
            b.subtitle = MODES[i][2];
            b.tag = MODES[i][0];
            b.onClick = () -> { if (!running) selectMode(b.tag); };
            modeButtons[i] = b;
            content.add(b);
        }
        start.purple = true;
        start.titleFont = Theme.pixel(24);
        start.onClick = this::startScan;
        stop.visible = false;
        stop.onClick = () -> { if (current != null) { current.stop = true; stop.setEnabled(false); stop.text = "Stopping..."; } };
        content.add(start);
        content.add(stop);

        content.add(launcherTitle);
        for (int i = 0; i < LAUNCHERS.length; i++) {
            PixelButton b = new PixelButton(LAUNCHERS[i][1]);
            b.tag = LAUNCHERS[i][0];
            b.titleFont = Theme.pixel(13);
            b.onClick = () -> { if (!running) pickLauncher(b.tag); };
            launcherButtons[i] = b;
            content.add(b);
        }

        admin.text = isAdmin ? "Administrator" : "Restart as admin";
        admin.setEnabled(!isAdmin && Sys.WINDOWS);
        admin.onClick = this::restartAsAdmin;
        content.add(admin);

        String[] labels = { "Verified", "Unknown", "Check", "Cheats", "Suspicious" };
        for (int i = 0; i < stats.length; i++) { stats[i] = new StatBox(labels[i]); content.add(stats[i]); }

        output.setEditable(false);
        output.setBackground(Theme.BG2);
        output.setForeground(Theme.TEXT);
        output.setFont(Theme.mono(15));
        output.setBorder(BorderFactory.createEmptyBorder());
        output.setCaretColor(Theme.BG2);
        outScroll.setBorder(BorderFactory.createEmptyBorder());
        outScroll.getViewport().setBackground(Theme.BG2);
        outScroll.getVerticalScrollBar().setBackground(Theme.BG2);
        outPanel.setBackground(Theme.BG2);
        outPanel.add(outScroll);
        content.add(outPanel);

        code.setEditable(false);
        code.setHorizontalAlignment(SwingConstants.CENTER);
        code.setBackground(Theme.BG2);
        code.setForeground(Theme.GOLD);
        code.setCaretColor(Theme.GOLD);
        code.setFont(Theme.mono(24));
        code.setBorder(BorderFactory.createLineBorder(Theme.STONE));
        send.purple = true;
        send.setEnabled(false);
        send.onClick = this::sendReport;
        sendBar.add(sendTitle);
        sendBar.add(sendHint);
        sendBar.add(code);
        sendBar.add(send);
        content.add(sendBar);

        content.setLayout(null);
        content.addComponentListener(new ComponentAdapter() { @Override public void componentResized(ComponentEvent e) { layoutAll(); } });
        pack();
        setLocationRelativeTo(null);
        layoutAll();
        selectMode(mode);
        showNotice();
        flush.start();
    }

    // ------------------------------------------------------------------ layout

    private void layoutAll() {
        int W = content.getWidth(), H = content.getHeight();
        int pad = 24, header = 72, leftW = 372;
        int x = pad, y = header + pad;
        titleA.setBounds(x, y, leftW, 46); y += 46;
        titleB.setBounds(x, y, leftW, 50); y += 58;
        blurb.wrap = true;
        blurb.setBounds(x, y, leftW, 66); y += 76;
        for (PixelButton b : modeButtons) { b.setBounds(x, y, leftW, 58); y += 64; }
        y += 6;
        start.setBounds(x, y, leftW, 60);
        stop.setBounds(x, y, leftW, 60);
        y += 76;
        launcherTitle.setBounds(x, y, leftW, 22); y += 28;
        int gap = 6, room = H - pad - y;
        int cols = room / LAUNCHERS.length - gap >= 30 ? 1 : 2;
        int rows = (LAUNCHERS.length + cols - 1) / cols;
        int lw = (leftW - gap * (cols - 1)) / cols;
        int lh = Math.max(24, Math.min(40, room / rows - gap));
        for (int i = 0; i < launcherButtons.length; i++) launcherButtons[i].setBounds(x + (i % cols) * (lw + gap), y + (i / cols) * (lh + gap), lw, lh);

        admin.setBounds(W - pad - 280, (header - 40) / 2, 280, 40);

        int rx = pad + leftW + pad, rw = W - rx - pad;
        int sy = header + pad, sh = 88, sg = 10;
        int bw = (rw - sg * (stats.length - 1)) / stats.length;
        for (int i = 0; i < stats.length; i++) stats[i].setBounds(rx + i * (bw + sg), sy, bw, sh);

        int barH = 112, by = H - pad - barH;
        sendBar.setBounds(rx, by, rw, barH);
        int ip = 16, sendW = 140, idW = 170, fieldH = 44;
        send.setBounds(rw - ip - sendW, (barH - fieldH) / 2, sendW, fieldH);
        code.setBounds(rw - ip - sendW - 10 - idW, (barH - 40) / 2, idW, 40);
        int textW = Math.max(120, code.getX() - ip - 16);
        sendTitle.setBounds(ip, 14, textW, 24);
        sendHint.wrap = true;
        sendHint.setBounds(ip, 42, textW, barH - 50);

        int oy = sy + sh + 14;
        outPanel.setBounds(rx, oy, rw, by - 14 - oy);
        outScroll.setBounds(10, 10, outPanel.getWidth() - 20, outPanel.getHeight() - 20);
        content.repaint();
    }

    // ------------------------------------------------------------------ modes and scanning

    private boolean scansMods() { return mode.equals("mods") || mode.equals("full"); }

    private void selectMode(String m) {
        mode = m;
        for (PixelButton b : modeButtons) { b.selected = b.tag.equals(m); b.repaint(); }
        updateLaunchers();
    }

    private void pickLauncher(String l) {
        if (l.equals("other")) {
            JFileChooser fc = new JFileChooser(otherFolder);
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("Pick your mods folder (or the launcher folder that holds it)");
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null) return;
            otherFolder = fc.getSelectedFile().getPath();
        }
        launcher = l;
        updateLaunchers();
    }

    private void updateLaunchers() {
        boolean on = scansMods() && !running;
        for (PixelButton b : launcherButtons) {
            b.selected = b.tag.equals(launcher);
            b.setEnabled(on);
            if (b.tag.equals("other")) b.text = launcher.equals("other") && otherFolder != null ? new java.io.File(otherFolder).getName() : "Other folder...";
            b.repaint();
        }
    }

    private void startScan() {
        if (running) return;
        running = true;
        report = new Report();
        reportJson = null;
        suspicious.clear();
        for (StatBox s : stats) s.set("-", Theme.DIM);
        for (PixelButton b : modeButtons) b.setEnabled(false);
        start.setEnabled(false); start.text = "Scanning...";
        send.setEnabled(false); send.text = "Get code";
        code.setText("");
        sendHint.color = Theme.MUTED; sendHint.setText(HINT_BEFORE);
        boolean live = mode.equals("injection") || mode.equals("full");
        stop.visible = live; stop.setEnabled(live); stop.text = "Stop live watch";
        start.visible = !live;
        output.setText("");
        updateLaunchers();

        Options opt = new Options();
        opt.mode = mode;
        opt.launcher = launcher.equals("other") ? "all" : launcher;
        if (launcher.equals("other") && otherFolder != null) opt.path.add(otherFolder);
        current = opt;
        Out out = new Out() {
            @Override public void text(C color, String text) { queue.add(new Object[] { color, text }); }
            @Override public void progress(String m) { progress = m; SwingUtilities.invokeLater(() -> content.repaint(0, 0, content.getWidth(), 72)); }
            @Override public void stat(String key, int value) { queue.add(new Object[] { key, value }); }
        };
        content.repaint();
        Thread t = new Thread(() -> {
            Scan.run(out, opt, report);
            String json = report.isEmpty() ? null : Scan.reportJson(opt, report);
            SwingUtilities.invokeLater(() -> onFinished(json));
        }, "ModAnzer scan");
        t.setDaemon(true);
        t.start();
    }

    private void onFinished(String json) {
        flush();
        running = false;
        progress = "";
        for (PixelButton b : modeButtons) b.setEnabled(true);
        updateLaunchers();
        start.setEnabled(true); start.text = "Scan again"; start.visible = true;
        stop.visible = false;
        if (json != null) {
            reportJson = json;
            send.setEnabled(true);
            sendHint.color = Theme.TEXT; sendHint.setText(HINT_READY);
        }
        content.repaint();
    }

    // ------------------------------------------------------------------ output

    private static Color map(Out.C c) {
        return switch (c) {
            case GREEN -> Theme.WIN;
            case RED -> Theme.LOSS;
            case YELLOW -> Theme.GOLD;
            case DARKYELLOW -> new Color(217, 164, 65);
            case CYAN -> new Color(127, 211, 255);
            case SECTION -> Theme.PURPLE;
            case MAGENTA -> new Color(208, 140, 255);
            case GRAY -> new Color(185, 178, 204);
            case DIM -> Theme.DIM;
            default -> Theme.TEXT;
        };
    }

    private void flush() {
        if (queue.isEmpty()) return;
        StyledDocument doc = output.getStyledDocument();
        int n = 0;
        Object[] item;
        try {
            while (n++ < 4000 && (item = queue.poll()) != null) {
                if (item[0] instanceof Out.C c) {
                    SimpleAttributeSet a = new SimpleAttributeSet();
                    StyleConstants.setForeground(a, map(c));
                    String text = (String) item[1];
                    doc.insertString(doc.getLength(), text, a);
                    parseNoticeLine(text);
                } else {
                    applyStat((String) item[0], (Integer) item[1]);
                }
            }
        } catch (BadLocationException ignored) { }
        output.setCaretPosition(doc.getLength());
    }

    private void parseNoticeLine(String text) { }

    private void applyStat(String key, int v) {
        switch (key) {
            case "verified" -> stats[0].set(String.valueOf(v), Theme.WIN);
            case "unknown" -> stats[1].set(String.valueOf(v), Theme.GOLD);
            case "check" -> stats[2].set(String.valueOf(v), v > 0 ? Theme.GOLD : Theme.WIN);
            case "cheat" -> stats[3].set(String.valueOf(v), v > 0 ? Theme.LOSS : Theme.WIN);
            case "suspicious" -> {
                suspicious.add(v);
                int total = suspicious.stream().mapToInt(Integer::intValue).sum();
                stats[4].set(String.valueOf(total), total > 0 ? Theme.LOSS : Theme.WIN);
            }
            default -> { }
        }
    }

    private void showNotice() {
        String[][] lines = {
            { "W", "This is NOT a hacking or malware tool. It only READS, and only:" },
            { "G", "  Mod Analyzer      fingerprints your mods and looks them up on Modrinth, Megabase and CurseForge," },
            { "G", "                    and searches unknown mods for known cheat names" },
            { "G", "  Injection Checker reads how Minecraft was started, which DLLs are in it and its memory (read-only)," },
            { "G", "                    then watches it live" },
            { "G", "  System Check      reads Windows' records of programs that ran, cleared logs, USB drives," },
            { "G", "                    the Recycle Bin and Minecraft account NAMES" },
            { "T", "" },
            { "W", "It does NOT:" },
            { "G", "  - upload your files, or send anything unless you press Get code at the end" },
            { "G", "  - read passwords, tokens, emails, browser data or Discord" },
            { "G", "  - change the game's memory, or change, move or delete any files" },
            { "T", "" },
            { "D", "Source code: https://github.com/PrimeTiers/ModAnzer" },
            { "T", "" },
            { "Y", "Pick a check on the left and press Start scan." } };
        for (String[] l : lines) {
            Out.C c = switch (l[0]) { case "W" -> Out.C.WHITE; case "G" -> Out.C.GRAY; case "D" -> Out.C.DIM; case "Y" -> Out.C.YELLOW; default -> Out.C.TEXT; };
            queue.add(new Object[] { c, l[1] + "\n" });
        }
    }

    // ------------------------------------------------------------------ admin and sending

    private void restartAsAdmin() {
        if (running || !Sys.WINDOWS) return;
        try {
            String cmd = ProcessHandle.current().info().command().orElse(null);
            if (cmd == null) return;
            String args = "--mode " + mode;
            if (new java.io.File(cmd).getName().toLowerCase().startsWith("java")) {
                String jar = new java.io.File(Gui.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
                args = "--enable-native-access=ALL-UNNAMED -jar \"" + jar + "\" " + args;
            }
            Sys.run(20, "powershell.exe", "-NoProfile", "-Command",
                "Start-Process -FilePath '" + cmd.replace("'", "''") + "' -ArgumentList '" + args.replace("'", "''") + "' -Verb RunAs");
            dispose();
            System.exit(0);
        } catch (Exception ignored) {
            // the person said no to the Windows prompt
        }
    }

    private void sendReport() {
        if (reportJson == null) return;
        send.setEnabled(false); send.text = "Sending";
        sendHint.color = Theme.MUTED; sendHint.setText("Sending...");
        String json = reportJson;
        Thread t = new Thread(() -> {
            String codeText = null, error = null;
            try { codeText = Report.send(json); } catch (Exception e) { error = e.getMessage(); }
            String c = codeText, err = error;
            SwingUtilities.invokeLater(() -> {
                if (c != null) {
                    send.text = "Sent";
                    code.setText(c);
                    try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(c), null); } catch (RuntimeException ignored) { }
                    sendHint.color = Theme.WIN;
                    sendHint.setText("Give this code to staff (it is copied). Only staff can see your results with it.");
                } else {
                    send.setEnabled(true); send.text = "Retry";
                    sendHint.color = Theme.LOSS;
                    sendHint.setText("Could not send: " + err);
                }
                content.repaint();
            });
        }, "ModAnzer send");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ painting

    private final class Content extends JPanel {
        Content() { super(null); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            int W = getWidth(), header = 72, pad = 24;
            Theme.fill(g, Theme.BG, 0, 0, W, getHeight());
            Theme.grid(g, new Rectangle(0, 0, W, getHeight()));
            Theme.fill(g, new Color(0x11, 0x0d, 0x1a, 240), 0, 0, W, header);
            Theme.fill(g, new Color(157, 107, 255, 46), 0, header, W, 2);
            if (logo != null) g.drawImage(logo, pad, (header - 32) / 2, 32, 32, null);
            Font wm = Theme.pixel(22);
            Theme.hints(g, wm);
            g.setFont(wm);
            int wx = pad + 32 + 12;
            int mw = g.getFontMetrics().stringWidth("Mod"), aw = g.getFontMetrics().stringWidth("Anzer");
            Theme.text(g, "Mod", wm, Theme.TEXT, wx, 0, mw + 40, header, 0);
            Theme.text(g, "Anzer", wm, Theme.PURPLE, wx + mw + 4, 0, aw + 40, header, 0);
            Theme.text(g, "for PrimeTiers screenshares", Theme.sans(13), Theme.DIM, wx + mw + aw + 18, 0, 260, header, 0);
            if (running) {
                String p = progress;
                Theme.text(g, p == null || p.isEmpty() ? "Scanning..." : p, Theme.sans(13), Theme.GOLD, 0, 0, admin.getX() - 16, header, 2);
            }
        }
    }

    private static class Panel extends JPanel {
        Panel() { super(null); setBackground(Theme.PANEL); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            Theme.fill(g, getBackground(), 0, 0, getWidth(), getHeight());
            Theme.bevel(g, new Rectangle(0, 0, getWidth(), getHeight()), false);
        }
    }

    /** Text drawn with the pixel font. */
    private static class Label extends JComponent {
        String text;
        final Font font;
        Color color;
        boolean wrap;

        Label(String text, Font font, Color color) { this.text = text; this.font = font; this.color = color; }

        void setText(String t) { text = t; repaint(); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            if (!wrap) { Theme.text(g, text, font, color, 0, 0, getWidth(), getHeight(), 0); return; }
            Theme.hints(g, font);
            g.setFont(font);
            g.setColor(color);
            java.awt.FontMetrics fm = g.getFontMetrics();
            int y = fm.getAscent();
            StringBuilder line = new StringBuilder();
            for (String word : text.split(" ")) {
                if (fm.stringWidth(line + word) > getWidth() && line.length() > 0) { g.drawString(line.toString(), 0, y); y += fm.getHeight(); line.setLength(0); }
                line.append(word).append(' ');
            }
            g.drawString(line.toString(), 0, y);
        }
    }

    /** One number in a box, like the "Ranked players" boxes on the site. */
    private static final class StatBox extends JComponent {
        final String label;
        String value = "-";
        Color valueColor = Theme.DIM;
        final Font labelFont = Theme.sans(13), valueFont = Theme.pixel(32);

        StatBox(String label) { this.label = label; }

        void set(String v, Color c) { value = v; valueColor = c; repaint(); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            int w = getWidth(), h = getHeight();
            Theme.fill(g, Theme.PANEL, 0, 0, w, h);
            Theme.bevel(g, new Rectangle(0, 0, w, h), false);
            Theme.text(g, label, labelFont, Theme.MUTED, 0, 8, w, (int) (h * 0.3), 1);
            Theme.text(g, value, valueFont, valueColor, 6, (int) (h * 0.38), w - 12, (int) (h * 0.56), 1);
        }
    }

    /** Square, bevelled button like the ones on primetiers.net. */
    private static final class PixelButton extends JComponent {
        String text, subtitle = "", tag = "";
        boolean purple, selected, visible = true;
        Font titleFont = Theme.pixel(16);
        final Font subFont = Theme.sans(13);
        Runnable onClick;
        private boolean hover, down;

        PixelButton(String text) {
            this.text = text;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; down = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { down = true; repaint(); }
                @Override public void mouseReleased(MouseEvent e) {
                    boolean click = down && isEnabled() && contains(e.getPoint());
                    down = false; repaint();
                    if (click && onClick != null) onClick.run();
                }
            });
        }

        @Override public boolean isVisible() { return visible && super.isVisible(); }

        @Override protected void paintComponent(Graphics g0) {
            if (!visible) return;
            Graphics2D g = (Graphics2D) g0;
            int w = getWidth(), h = getHeight();
            boolean p = purple || selected;
            boolean en = isEnabled();
            Color fill = !en ? Theme.PANEL2 : p ? (hover ? Theme.PURPLE : Theme.PURPLE_D) : (hover ? Theme.STONE_HI : Theme.STONE);
            Theme.fill(g, fill, 0, 0, w, h);
            Theme.bevel(g, new Rectangle(0, 0, w, h), true);
            int pad = 16, shift = down ? 1 : 0;
            Color fg = en ? Color.WHITE : Theme.DIM;
            if (subtitle.isEmpty()) {
                Theme.text(g, text, titleFont, fg, pad, shift, w - 2 * pad, h, 1);
            } else {
                int th = g.getFontMetrics(titleFont).getHeight(), sh = g.getFontMetrics(subFont).getHeight();
                int top = (h - th - sh - 2) / 2 + shift;
                Theme.text(g, text, titleFont, fg, pad, top, w - 2 * pad, th, 0);
                Theme.text(g, subtitle, subFont, en ? (p ? new Color(225, 214, 255) : Theme.MUTED) : Theme.DIM, pad, top + th + 2, w - 2 * pad, sh, 0);
            }
        }
    }
}
