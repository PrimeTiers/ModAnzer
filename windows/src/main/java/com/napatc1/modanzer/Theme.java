package com.napatc1.modanzer;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.io.InputStream;

/** PrimeTiers colours (from primetiers.net) and shared drawing helpers. */
final class Theme {
    private Theme() {}

    static final Color BG = new Color(0x110d1a), BG2 = new Color(0x161120), PANEL = new Color(0x1c1628), PANEL2 = new Color(0x241d33);
    static final Color STONE = new Color(0x3b3350), STONE_HI = new Color(0x4a4066);
    static final Color PURPLE = new Color(0x9d6bff), PURPLE_D = new Color(0x6e44d9);
    static final Color TEXT = new Color(0xece8f6), MUTED = new Color(0x9a92b0), DIM = new Color(0x6b6480);
    static final Color GOLD = new Color(0xffc83d), WIN = new Color(0x5ee07a), LOSS = new Color(0xff6363);

    private static Font pixelBase;

    /** The Silkscreen pixel font (SIL Open Font License), or a bold sans font if it can't be loaded. */
    static Font pixel(float px) {
        try {
            if (pixelBase == null) {
                try (InputStream in = Theme.class.getResourceAsStream("/Silkscreen-Bold.ttf")) {
                    pixelBase = Font.createFont(Font.TRUETYPE_FONT, in);
                }
            }
            return pixelBase.deriveFont(Font.PLAIN, px);
        } catch (Exception e) {
            return new Font("Segoe UI", Font.BOLD, Math.round(px * 0.8f));
        }
    }

    static boolean isPixel(Font f) { return f.getFamily().startsWith("Silkscreen"); }

    static Font sans(float px) { return new Font("Segoe UI", Font.PLAIN, Math.round(px * 0.8f)); }

    static Font mono(float px) {
        for (String n : new String[] { "Cascadia Mono", "Consolas" }) {
            Font f = new Font(n, Font.PLAIN, Math.round(px * 0.8f));
            if (f.getFamily().equalsIgnoreCase(n)) return f;
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, Math.round(px * 0.8f));
    }

    /** Minecraft-style bevel: light top/left edge, dark bottom/right edge, optional black outline. */
    static void bevel(Graphics2D g, Rectangle r, boolean outline) {
        int b = 2;
        g.setColor(new Color(255, 255, 255, 46));
        g.fillRect(r.x, r.y, r.width, b);
        g.fillRect(r.x, r.y, b, r.height);
        g.setColor(new Color(0, 0, 0, 90));
        g.fillRect(r.x, r.y + r.height - b - 1, r.width, b + 1);
        g.fillRect(r.x + r.width - b, r.y, b, r.height);
        if (outline) {
            g.setColor(Color.BLACK);
            g.fillRect(r.x, r.y, r.width, b);
            g.fillRect(r.x, r.y + r.height - b, r.width, b);
            g.fillRect(r.x, r.y, b, r.height);
            g.fillRect(r.x + r.width - b, r.y, b, r.height);
        }
    }

    /** The faint 32px grid primetiers.net draws on its background. */
    static void grid(Graphics2D g, Rectangle r) {
        g.setColor(new Color(255, 255, 255, 6));
        for (int x = r.x; x < r.x + r.width; x += 32) g.fillRect(x, r.y, 2, r.height);
        for (int y = r.y; y < r.y + r.height; y += 32) g.fillRect(r.x, y, r.width, 2);
    }

    static void hints(Graphics2D g, Font f) {
        boolean pixel = isPixel(f);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, pixel ? RenderingHints.VALUE_TEXT_ANTIALIAS_OFF : RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
    }

    /** Draws text inside a box. The pixel font is shrunk to fit instead of being trimmed. */
    static void text(Graphics2D g, String s, Font font, Color c, int x, int y, int w, int h, int align) {
        if (s == null || s.isEmpty()) return;
        Font use = font;
        hints(g, use);
        FontMetrics fm = g.getFontMetrics(use);
        while (fm.stringWidth(s) > w && use.getSize() > 8) {
            use = use.deriveFont((float) use.getSize() - 1);
            fm = g.getFontMetrics(use);
        }
        g.setFont(use);
        g.setColor(c);
        int tx = align == 0 ? x : align == 1 ? x + (w - fm.stringWidth(s)) / 2 : x + w - fm.stringWidth(s);
        int ty = y + (h - fm.getHeight()) / 2 + fm.getAscent();
        g.drawString(s, tx, ty);
    }

    static void fill(Graphics2D g, Color c, int x, int y, int w, int h) {
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(c);
        g.fillRect(x, y, w, h);
    }
}
