using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace ModAnzer
{
    internal static class Program
    {
        [STAThread]
        private static int Main(string[] args)
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            string mode = null, shot = null, scan = null;
            for (int i = 0; i < args.Length - 1; i++)
            {
                if (args[i] == "--mode") mode = args[i + 1];
                if (args[i] == "--screenshot") shot = args[i + 1];
                if (args[i] == "--scan") scan = args[i + 1];
            }
            if (mode != null && Array.IndexOf(new[] { "mods", "injection", "system", "full" }, mode) < 0) mode = null;

            if (shot != null) return Screenshot(shot, scan);
            Application.Run(new MainForm(mode));
            return 0;
        }

        // Used by the GitHub build to save a preview of the window.
        private static int Screenshot(string path, string scan)
        {
            using (var f = new MainForm(null))
            {
                f.StartPosition = FormStartPosition.Manual;
                f.Location = new Point(0, 0);
                f.Show();
                if (scan == null) f.LoadDemo();
                else
                {
                    // real scan, used by the build as a test of the embedded script
                    f.StartScanFor(scan);
                    var until = DateTime.Now.AddMinutes(8);
                    while (DateTime.Now < until && (f.IsScanning || !f.Visible)) { Application.DoEvents(); System.Threading.Thread.Sleep(50); }
                    for (int i = 0; i < 10; i++) { Application.DoEvents(); System.Threading.Thread.Sleep(50); }
                    System.IO.File.WriteAllText(path + ".txt", f.OutputText);
                }
                for (int i = 0; i < 30; i++) { Application.DoEvents(); System.Threading.Thread.Sleep(20); }
                using (var bmp = new Bitmap(f.Width, f.Height))
                {
                    using (var g = Graphics.FromImage(bmp))
                    {
                        IntPtr hdc = g.GetHdc();
                        bool ok = PrintWindow(f.Handle, hdc, 2); // PW_RENDERFULLCONTENT
                        g.ReleaseHdc(hdc);
                        if (!ok) f.DrawToBitmap(bmp, new Rectangle(0, 0, f.Width, f.Height));
                    }
                    bmp.Save(path, ImageFormat.Png);
                }
                f.Close();
            }
            return 0;
        }

        [DllImport("user32.dll")]
        private static extern bool PrintWindow(IntPtr hwnd, IntPtr hdc, uint flags);
    }
}
