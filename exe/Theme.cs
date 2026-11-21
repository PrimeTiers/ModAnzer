using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace ModAnzer
{
    /// <summary>PrimeTiers colours (from primetiers.net) and shared drawing helpers.</summary>
    internal static class Theme
    {
        public static readonly Color Bg = Hex("#110d1a");
        public static readonly Color Bg2 = Hex("#161120");
        public static readonly Color Panel = Hex("#1c1628");
        public static readonly Color Panel2 = Hex("#241d33");
        public static readonly Color Row = Hex("#201a2d");
        public static readonly Color Stone = Hex("#3b3350");
        public static readonly Color StoneHi = Hex("#4a4066");
        public static readonly Color Purple = Hex("#9d6bff");
        public static readonly Color PurpleD = Hex("#6e44d9");
        public static readonly Color PurpleDD = Hex("#2a1a5e");
        public static readonly Color Text = Hex("#ece8f6");
        public static readonly Color Muted = Hex("#9a92b0");
        public static readonly Color Dim = Hex("#6b6480");
        public static readonly Color Gold = Hex("#ffc83d");
        public static readonly Color Win = Hex("#5ee07a");
        public static readonly Color Loss = Hex("#ff6363");

        public static float Scale = 1f;
        public static int S(int px) { return (int)Math.Round(px * Scale); }

        public static Color Hex(string h)
        {
            h = h.TrimStart('#');
            return Color.FromArgb(Convert.ToInt32(h.Substring(0, 2), 16), Convert.ToInt32(h.Substring(2, 2), 16), Convert.ToInt32(h.Substring(4, 2), 16));
        }

        // ---- pixel font (Silkscreen, SIL Open Font License) ----
        private static PrivateFontCollection _fonts;
        private static IntPtr _fontMem = IntPtr.Zero;

        public static Font Pixel(float px)
        {
            try
            {
                if (_fonts == null)
                {
                    byte[] data;
                    using (var s = typeof(Theme).Assembly.GetManifestResourceStream("Silkscreen-Bold.ttf"))
                    using (var ms = new MemoryStream()) { s.CopyTo(ms); data = ms.ToArray(); }
                    _fontMem = Marshal.AllocCoTaskMem(data.Length); // kept for the app's lifetime
                    Marshal.Copy(data, 0, _fontMem, data.Length);
                    _fonts = new PrivateFontCollection();
                    _fonts.AddMemoryFont(_fontMem, data.Length);
                }
                return new Font(_fonts.Families[0], px * Scale, FontStyle.Regular, GraphicsUnit.Pixel);
            }
            catch
            {
                return new Font("Segoe UI", px * 0.8f * Scale, FontStyle.Bold, GraphicsUnit.Pixel);
            }
        }

        public static Font Sans(float px, FontStyle style = FontStyle.Regular)
        {
            return new Font("Segoe UI", px * Scale, style, GraphicsUnit.Pixel);
        }

        public static Font Mono(float px)
        {
            foreach (var name in new[] { "Cascadia Mono", "Consolas" })
            {
                using (var f = new Font(name, px * Scale, GraphicsUnit.Pixel))
                    if (f.Name == name) return new Font(name, px * Scale, GraphicsUnit.Pixel);
            }
            return new Font(FontFamily.GenericMonospace, px * Scale, GraphicsUnit.Pixel);
        }

        /// <summary>Minecraft-style bevel: light top/left edge, dark bottom/right edge, black outline.</summary>
        public static void Bevel(Graphics g, Rectangle r, bool outline)
        {
            int b = Math.Max(2, S(2));
            using (var light = new SolidBrush(Color.FromArgb(46, 255, 255, 255)))
            using (var dark = new SolidBrush(Color.FromArgb(90, 0, 0, 0)))
            {
                g.FillRectangle(light, r.X, r.Y, r.Width, b);
                g.FillRectangle(light, r.X, r.Y, b, r.Height);
                g.FillRectangle(dark, r.X, r.Bottom - b - 1, r.Width, b + 1);
                g.FillRectangle(dark, r.Right - b, r.Y, b, r.Height);
            }
            if (outline)
            {
                using (var p = new Pen(Color.Black, b)) { p.Alignment = PenAlignment.Inset; g.DrawRectangle(p, r); }
            }
        }

        /// <summary>The faint 32px grid primetiers.net draws on its background.</summary>
        public static void Grid(Graphics g, Rectangle r)
        {
            int step = S(32);
            int w = Math.Max(1, S(2));
            using (var b = new SolidBrush(Color.FromArgb(6, 255, 255, 255)))
            {
                for (int x = r.X; x < r.Right; x += step) g.FillRectangle(b, x, r.Y, w, r.Height);
                for (int y = r.Y; y < r.Bottom; y += step) g.FillRectangle(b, r.X, y, r.Width, w);
            }
        }

        private static bool IsPixel(Font f) { return f.FontFamily.Name.StartsWith("Silkscreen"); }

        /// <summary>Width of the text as it is really drawn (no GDI+ padding).</summary>
        public static float TextWidth(Graphics g, string text, Font font)
        {
            g.TextRenderingHint = IsPixel(font) ? TextRenderingHint.SingleBitPerPixelGridFit : TextRenderingHint.ClearTypeGridFit;
            using (var sf = (StringFormat)StringFormat.GenericTypographic.Clone())
            {
                sf.FormatFlags |= StringFormatFlags.MeasureTrailingSpaces | StringFormatFlags.NoWrap;
                return g.MeasureString(text, font, PointF.Empty, sf).Width;
            }
        }

        public static void DrawText(Graphics g, string text, Font font, Color color, RectangleF r, StringAlignment h = StringAlignment.Near, StringAlignment v = StringAlignment.Center)
        {
            if (string.IsNullOrEmpty(text)) return;
            bool pixel = IsPixel(font);
            g.TextRenderingHint = pixel ? TextRenderingHint.SingleBitPerPixelGridFit : TextRenderingHint.ClearTypeGridFit;
            Font use = font;
            try
            {
                if (pixel)
                {
                    // The pixel font has no usable "..." glyph (it draws as "_") and GDI+ drops the whole
                    // line when it is taller than the box. So never trim or clip it: shrink it to fit instead.
                    float w = TextWidth(g, text, use);
                    while (w > r.Width && use.Size > 8)
                    {
                        var smaller = new Font(use.FontFamily, use.Size - 1, use.Style, GraphicsUnit.Pixel);
                        if (!ReferenceEquals(use, font)) use.Dispose();
                        use = smaller;
                        w = TextWidth(g, text, use);
                    }
                }
                using (var sf = new StringFormat(StringFormatFlags.NoWrap | (pixel ? StringFormatFlags.NoClip : 0)) { Alignment = h, LineAlignment = v, Trimming = pixel ? StringTrimming.None : StringTrimming.EllipsisCharacter })
                using (var br = new SolidBrush(color))
                    g.DrawString(text, use, br, r, sf);
            }
            finally
            {
                if (!ReferenceEquals(use, font)) use.Dispose();
            }
        }
    }

    /// <summary>Square, bevelled button like the ones on primetiers.net.</summary>
    internal class PixelButton : Control
    {
        public enum Kind { Stone, Purple }
        public Kind Style = Kind.Stone;
        public bool Selected;
        public string Subtitle = "";
        public Font TitleFont;
        public Font SubFont;
        private bool _hover, _down;

        public PixelButton()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint | ControlStyles.ResizeRedraw, true);
            Cursor = Cursors.Hand;
            TitleFont = Theme.Pixel(16);
            SubFont = Theme.Sans(13);
        }

        protected override void OnMouseEnter(EventArgs e) { _hover = true; Invalidate(); base.OnMouseEnter(e); }
        protected override void OnMouseLeave(EventArgs e) { _hover = false; _down = false; Invalidate(); base.OnMouseLeave(e); }
        protected override void OnMouseDown(MouseEventArgs e) { _down = true; Invalidate(); base.OnMouseDown(e); }
        protected override void OnMouseUp(MouseEventArgs e) { _down = false; Invalidate(); base.OnMouseUp(e); }
        protected override void OnEnabledChanged(EventArgs e) { Cursor = Enabled ? Cursors.Hand : Cursors.Default; Invalidate(); base.OnEnabledChanged(e); }
        protected override void OnTextChanged(EventArgs e) { Invalidate(); base.OnTextChanged(e); }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            var r = ClientRectangle;
            bool purple = Style == Kind.Purple || Selected;
            Color fill = purple ? (_hover && Enabled ? Theme.Purple : Theme.PurpleD) : (_hover && Enabled ? Theme.StoneHi : Theme.Stone);
            if (!Enabled) fill = Theme.Panel2;
            using (var b = new SolidBrush(fill)) g.FillRectangle(b, r);
            Theme.Bevel(g, r, true);
            int pad = Theme.S(16);
            int shift = _down ? Theme.S(1) : 0;
            Color fg = Enabled ? Color.White : Theme.Dim;
            if (string.IsNullOrEmpty(Subtitle))
            {
                Theme.DrawText(g, Text, TitleFont, fg, new RectangleF(pad, shift, r.Width - 2 * pad, r.Height), StringAlignment.Center);
            }
            else
            {
                float th = TitleFont.GetHeight(g);
                float sh = SubFont.GetHeight(g);
                float top = (r.Height - th - sh - Theme.S(2)) / 2f + shift;
                Theme.DrawText(g, Text, TitleFont, fg, new RectangleF(pad, top, r.Width - 2 * pad, th), StringAlignment.Near, StringAlignment.Near);
                Theme.DrawText(g, Subtitle, SubFont, Enabled ? (purple ? Color.FromArgb(225, 214, 255) : Theme.Muted) : Theme.Dim,
                    new RectangleF(pad, top + th + Theme.S(2), r.Width - 2 * pad, sh), StringAlignment.Near, StringAlignment.Near);
            }
        }
    }

    /// <summary>Dark panel with the site's bevel, optionally a heading.</summary>
    internal class BevelPanel : Panel
    {
        public BevelPanel()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint | ControlStyles.ResizeRedraw, true);
            BackColor = Theme.Panel;
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            using (var b = new SolidBrush(BackColor)) e.Graphics.FillRectangle(b, ClientRectangle);
            Theme.Bevel(e.Graphics, ClientRectangle, false);
        }
    }

    /// <summary>One number in a box, like the "Ranked players 696" boxes on the site.</summary>
    internal class StatBox : Control
    {
        public string Label = "";
        public string Value = "-";
        public Color ValueColor = Theme.Dim;
        private readonly Font _labelFont = Theme.Sans(13);
        private readonly Font _valueFont = Theme.Pixel(32);

        public StatBox()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint | ControlStyles.ResizeRedraw, true);
        }

        public void Set(string value, Color color) { Value = value; ValueColor = color; Invalidate(); }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            using (var b = new SolidBrush(Theme.Panel)) g.FillRectangle(b, ClientRectangle);
            Theme.Bevel(g, ClientRectangle, false);
            int h = ClientRectangle.Height;
            Theme.DrawText(g, Label, _labelFont, Theme.Muted, new RectangleF(0, Theme.S(8), Width, h * 0.38f), StringAlignment.Center);
            Theme.DrawText(g, Value, _valueFont, ValueColor, new RectangleF(Theme.S(6), h * 0.38f, Width - Theme.S(12), h * 0.56f), StringAlignment.Center);
        }
    }

    /// <summary>Text drawn with GDI+ so the embedded pixel font works.</summary>
    internal class PixelLabel : Control
    {
        public StringAlignment Align = StringAlignment.Near;

        public PixelLabel()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint | ControlStyles.ResizeRedraw | ControlStyles.SupportsTransparentBackColor, true);
            BackColor = Color.Transparent;
        }

        protected override void OnTextChanged(EventArgs e) { Invalidate(); base.OnTextChanged(e); }

        protected override void OnPaint(PaintEventArgs e)
        {
            Theme.DrawText(e.Graphics, Text, Font, ForeColor, new RectangleF(0, 0, Width, Height), Align, StringAlignment.Near);
        }
    }
}
