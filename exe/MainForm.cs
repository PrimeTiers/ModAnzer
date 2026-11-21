using System;
using System.Collections.Concurrent;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace ModAnzer
{
    internal sealed class MainForm : Form
    {
        private static readonly string[][] Modes =
        {
            new[] { "mods", "Mod Analyzer", "Check every mods folder for cheats" },
            new[] { "injection", "Injection Checker", "Check the running game and watch it live" },
            new[] { "system", "System Check", "Deleted files, cleared logs, USB, tools" },
            new[] { "full", "Full Screenshare", "All three, one after another" },
        };

        private readonly PixelLabel _titleA = new PixelLabel();
        private readonly PixelLabel _titleB = new PixelLabel();
        private readonly Label _blurb = new Label();
        private readonly PixelButton[] _modeButtons = new PixelButton[4];
        private readonly PixelButton _start = new PixelButton();
        private readonly PixelButton _stop = new PixelButton();
        private readonly PixelButton _admin = new PixelButton();
        private readonly StatBox[] _stats = new StatBox[5];
        private readonly BevelPanel _outPanel = new BevelPanel();
        private readonly RichTextBox _out = new RichTextBox();
        private readonly BevelPanel _sendBar = new BevelPanel();
        private readonly PixelLabel _sendTitle = new PixelLabel();
        private readonly Label _sendHint = new Label();
        private readonly TextBox _discordId = new TextBox();
        private readonly PixelButton _send = new PixelButton();

        private readonly Icon _appIcon;
        private readonly Bitmap _logo;
        private readonly Font _wordmark;
        private readonly Font _tagline;
        private readonly bool _isAdmin;
        private readonly string _reportUrl;

        private string _mode = "mods";
        private ScanRunner _runner;
        private bool _running;
        private string _reportFile;
        private readonly ConcurrentQueue<Tuple<string, ConsoleColor?, bool>> _queue = new ConcurrentQueue<Tuple<string, ConsoleColor?, bool>>();
        private readonly Timer _flush = new Timer { Interval = 60 };
        private int _lineStart;
        private readonly StringBuilder _line = new StringBuilder();
        private int _suspicious;

        public MainForm(string initialMode)
        {
            using (var g = CreateGraphics()) Theme.Scale = Math.Max(1f, g.DpiX / 96f);

            Text = "ModAnzer";
            using (var s = typeof(MainForm).Assembly.GetManifestResourceStream("ModAnzer.ico")) _appIcon = new Icon(s);
            Icon = _appIcon;
            _logo = new Icon(_appIcon, Theme.S(32), Theme.S(32)).ToBitmap();
            _wordmark = Theme.Pixel(24);
            _tagline = Theme.Sans(13);
            BackColor = Theme.Bg;
            ForeColor = Theme.Text;
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(Theme.S(1120), Theme.S(760));
            MinimumSize = new Size(Theme.S(980), Theme.S(700));
            DoubleBuffered = true;
            SetStyle(ControlStyles.ResizeRedraw, true);

            _isAdmin = IsAdmin();
            _reportUrl = ReadReportUrl();
            if (!string.IsNullOrEmpty(initialMode)) _mode = initialMode;

            // left column
            _titleA.Text = "Get scanned."; _titleA.Font = Theme.Pixel(40); _titleA.ForeColor = Theme.Text;
            _titleB.Text = "Stay clean."; _titleB.Font = Theme.Pixel(40); _titleB.ForeColor = Theme.Gold;
            _blurb.Text = "Pick a check. ModAnzer only reads: it never changes, deletes or installs anything, and nothing is sent unless you press Send.";
            _blurb.Font = Theme.Sans(15); _blurb.ForeColor = Theme.Muted; _blurb.BackColor = Color.Transparent;
            Controls.AddRange(new Control[] { _titleA, _titleB, _blurb });

            for (int i = 0; i < Modes.Length; i++)
            {
                var b = new PixelButton { Text = Modes[i][1], Subtitle = Modes[i][2], Tag = Modes[i][0] };
                b.Click += (s, e) => { if (!_running) SelectMode((string)((Control)s).Tag); };
                _modeButtons[i] = b;
                Controls.Add(b);
            }
            _start.Text = "Start scan"; _start.Style = PixelButton.Kind.Purple; _start.TitleFont = Theme.Pixel(24);
            _start.Click += (s, e) => StartScan();
            _stop.Text = "Stop live watch"; _stop.Visible = false;
            _stop.Click += (s, e) => { if (_runner != null) { _runner.RequestStop(); _stop.Enabled = false; _stop.Text = "Stopping..."; } };
            Controls.AddRange(new Control[] { _start, _stop });

            // header: admin
            _admin.Text = _isAdmin ? "Administrator" : "Restart as admin";
            _admin.TitleFont = Theme.Pixel(16);
            _admin.Enabled = !_isAdmin;
            _admin.Click += (s, e) => RestartAsAdmin();
            Controls.Add(_admin);

            // right: stats + output
            string[] labels = { "Verified", "Unknown", "Check", "Cheats", "Suspicious" };
            for (int i = 0; i < _stats.Length; i++) { _stats[i] = new StatBox { Label = labels[i] }; Controls.Add(_stats[i]); }

            _out.ReadOnly = true;
            _out.BorderStyle = BorderStyle.None;
            _out.BackColor = Theme.Bg2;
            _out.ForeColor = Theme.Text;
            _out.Font = Theme.Mono(15);
            _out.WordWrap = false;
            _out.DetectUrls = false;
            _out.ScrollBars = RichTextBoxScrollBars.Both;
            _outPanel.BackColor = Theme.Bg2;
            _outPanel.Controls.Add(_out);
            Controls.Add(_outPanel);

            // send bar
            _sendTitle.Text = "Send results to PrimeTiers staff"; _sendTitle.Font = Theme.Pixel(16); _sendTitle.ForeColor = Theme.Text;
            _sendHint.Text = "Your Discord ID: Settings > Advanced > Developer Mode, then right-click your name > Copy User ID.";
            _sendHint.Font = Theme.Sans(13); _sendHint.ForeColor = Theme.Muted; _sendHint.BackColor = Theme.Panel;
            _discordId.BorderStyle = BorderStyle.FixedSingle; _discordId.BackColor = Theme.Bg2; _discordId.ForeColor = Theme.Text;
            _discordId.Font = Theme.Sans(18); _discordId.MaxLength = 20;
            _send.Text = "Send"; _send.Style = PixelButton.Kind.Purple; _send.Enabled = false;
            _send.Click += (s, e) => SendReport();
            _sendBar.Controls.AddRange(new Control[] { _sendTitle, _sendHint, _discordId, _send });
            Controls.Add(_sendBar);

            _flush.Tick += (s, e) => Flush();
            _flush.Start();
            Resize += (s, e) => DoLayout();
            Shown += (s, e) => SetCueBanner(_discordId, "e.g. 123456789012345678");

            ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
            SelectMode(_mode);
            ShowNotice();
            DoLayout();
        }

        // ------------------------------------------------------------------ layout & painting

        private void DoLayout()
        {
            int pad = Theme.S(24), header = Theme.S(72), leftW = Theme.S(372);
            int x = pad, y = header + pad;
            _titleA.SetBounds(x, y, leftW, Theme.S(46)); y += Theme.S(46);
            _titleB.SetBounds(x, y, leftW, Theme.S(50)); y += Theme.S(58);
            _blurb.SetBounds(x, y, leftW, Theme.S(66)); y += Theme.S(76);
            foreach (var b in _modeButtons) { b.SetBounds(x, y, leftW, Theme.S(64)); y += Theme.S(74); }
            y += Theme.S(8);
            _start.SetBounds(x, y, leftW, Theme.S(60)); y += Theme.S(70);
            _stop.SetBounds(x, y, leftW, Theme.S(46));

            _admin.SetBounds(ClientSize.Width - pad - Theme.S(220), (header - Theme.S(40)) / 2, Theme.S(220), Theme.S(40));

            int rx = pad + leftW + pad, rw = ClientSize.Width - rx - pad;
            int sy = header + pad, sh = Theme.S(88), gap = Theme.S(10);
            int bw = (rw - gap * (_stats.Length - 1)) / _stats.Length;
            for (int i = 0; i < _stats.Length; i++) _stats[i].SetBounds(rx + i * (bw + gap), sy, bw, sh);

            int barH = Theme.S(96);
            int by = ClientSize.Height - pad - barH;
            _sendBar.SetBounds(rx, by, rw, barH);
            int ip = Theme.S(16);
            int sendW = Theme.S(130), idW = Theme.S(240), fieldH = Theme.S(44);
            _send.SetBounds(rw - ip - sendW, (barH - fieldH) / 2, sendW, fieldH);
            _discordId.SetBounds(rw - ip - sendW - Theme.S(10) - idW, (barH - _discordId.Height) / 2, idW, _discordId.Height);
            int textW = Math.Max(Theme.S(120), _discordId.Left - ip - Theme.S(16));
            _sendTitle.SetBounds(ip, Theme.S(14), textW, Theme.S(24));
            _sendHint.SetBounds(ip, Theme.S(42), textW, barH - Theme.S(48));

            int oy = sy + sh + Theme.S(14);
            _outPanel.SetBounds(rx, oy, rw, by - Theme.S(14) - oy);
            int bev = Theme.S(10);
            _out.SetBounds(bev, bev, _outPanel.Width - 2 * bev, _outPanel.Height - 2 * bev);
            Invalidate();
        }

        protected override void OnPaintBackground(PaintEventArgs e)
        {
            var g = e.Graphics;
            using (var b = new SolidBrush(Theme.Bg)) g.FillRectangle(b, ClientRectangle);
            Theme.Grid(g, ClientRectangle);
            int header = Theme.S(72), pad = Theme.S(24);
            using (var b = new SolidBrush(Color.FromArgb(240, Theme.Bg))) g.FillRectangle(b, 0, 0, ClientSize.Width, header);
            using (var b = new SolidBrush(Color.FromArgb(46, Theme.Purple))) g.FillRectangle(b, 0, header, ClientSize.Width, Theme.S(2));
            g.DrawImage(_logo, pad, (header - _logo.Height) / 2);
            float wx = pad + _logo.Width + Theme.S(12);
            g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.SingleBitPerPixelGridFit;
            var mod = g.MeasureString("Mod", _wordmark, PointF.Empty, StringFormat.GenericTypographic);
            Theme.DrawText(g, "Mod", _wordmark, Theme.Text, new RectangleF(wx, 0, mod.Width + Theme.S(4), header));
            Theme.DrawText(g, "Anzer", _wordmark, Theme.Purple, new RectangleF(wx + mod.Width + Theme.S(2), 0, Theme.S(200), header));
            Theme.DrawText(g, "for PrimeTiers screenshares", _tagline, Theme.Dim, new RectangleF(wx + Theme.S(170), 0, Theme.S(320), header));
            if (_running)
            {
                Theme.DrawText(g, "Scanning...", _tagline, Theme.Gold, new RectangleF(0, 0, _admin.Left - Theme.S(16), header), StringAlignment.Far);
            }
        }

        // ------------------------------------------------------------------ modes & scanning

        private void SelectMode(string mode)
        {
            _mode = mode;
            foreach (var b in _modeButtons) { b.Selected = (string)b.Tag == mode; b.Invalidate(); }
        }

        private void StartScan()
        {
            if (_running) return;
            _running = true;
            _reportFile = null;
            _suspicious = 0;
            foreach (var s in _stats) s.Set("-", Theme.Gold);
            foreach (var b in _modeButtons) b.Enabled = false;
            _start.Enabled = false; _start.Text = "Scanning...";
            _send.Enabled = false; _send.Text = "Send";
            _sendHint.ForeColor = Theme.Muted;
            _sendHint.Text = "Your Discord ID: Settings > Advanced > Developer Mode, then right-click your name > Copy User ID.";
            bool live = _mode == "injection" || _mode == "full";
            _stop.Visible = live; _stop.Enabled = live; _stop.Text = "Stop live watch";
            _out.Clear(); _line.Clear(); _lineStart = 0;
            Invalidate();

            _runner = new ScanRunner();
            _runner.Output += (t, c, n) => _queue.Enqueue(Tuple.Create(t, c, n));
            _runner.Finished += code => BeginInvoke((Action)(() => OnFinished(code)));
            _runner.Start(_mode, false);
        }

        private void OnFinished(int code)
        {
            Flush();
            _running = false;
            foreach (var b in _modeButtons) b.Enabled = true;
            _start.Enabled = true; _start.Text = "Scan again";
            _stop.Visible = false;
            if (_runner != null && File.Exists(_runner.ReportFile) && new FileInfo(_runner.ReportFile).Length > 2)
            {
                _reportFile = _runner.ReportFile;
                _send.Enabled = true;
                _sendHint.ForeColor = Theme.Text;
                _sendHint.Text = "Scan finished. Enter your Discord ID and press Send to share the results with PrimeTiers staff.";
                _discordId.Focus();
            }
            Invalidate();
        }

        // ------------------------------------------------------------------ output

        private void Flush()
        {
            if (_queue.IsEmpty) return;
            SendMessage(_out.Handle, 0x000B, IntPtr.Zero, IntPtr.Zero); // WM_SETREDRAW off
            try
            {
                Tuple<string, ConsoleColor?, bool> item;
                int n = 0;
                while (n++ < 4000 && _queue.TryDequeue(out item)) Append(item.Item1, item.Item2, item.Item3);
            }
            finally
            {
                SendMessage(_out.Handle, 0x000B, new IntPtr(1), IntPtr.Zero); // WM_SETREDRAW on
                _out.SelectionStart = _out.TextLength;
                _out.ScrollToCaret();
                _out.Invalidate();
            }
        }

        private void Append(string text, ConsoleColor? color, bool noNewLine)
        {
            string[] parts = text.Replace("\r\n", "\n").Split('\r');
            for (int i = 0; i < parts.Length; i++)
            {
                if (i > 0)
                {
                    // carriage return: rewrite the current line (progress counters)
                    _out.Select(_lineStart, _out.TextLength - _lineStart);
                    _out.SelectedText = "";
                    _line.Clear();
                }
                string part = parts[i];
                if (part.Length == 0) continue;
                _out.SelectionStart = _out.TextLength;
                _out.SelectionLength = 0;
                _out.SelectionColor = Map(color);
                _out.SelectedText = part;
                int nl = part.LastIndexOf('\n');
                if (nl >= 0) { _lineStart = _out.TextLength - (part.Length - nl - 1); _line.Clear(); _line.Append(part.Substring(nl + 1)); }
                else _line.Append(part);
            }
            if (!noNewLine)
            {
                _out.SelectionStart = _out.TextLength;
                _out.SelectedText = "\n";
                ParseStats(_line.ToString());
                _line.Clear();
                _lineStart = _out.TextLength;
            }
        }

        private static Color Map(ConsoleColor? c)
        {
            if (c == null) return Theme.Text;
            switch (c.Value)
            {
                case ConsoleColor.Green: return Theme.Win;
                case ConsoleColor.Red: return Theme.Loss;
                case ConsoleColor.Yellow: return Theme.Gold;
                case ConsoleColor.DarkYellow: return Color.FromArgb(217, 164, 65);
                case ConsoleColor.Cyan: return Color.FromArgb(127, 211, 255);
                case ConsoleColor.DarkCyan: return Theme.Purple;
                case ConsoleColor.Magenta: return Color.FromArgb(208, 140, 255);
                case ConsoleColor.DarkMagenta: return Theme.PurpleD;
                case ConsoleColor.Gray: return Color.FromArgb(185, 178, 204);
                case ConsoleColor.DarkGray: return Theme.Dim;
                case ConsoleColor.DarkRed: return Color.FromArgb(200, 80, 80);
                case ConsoleColor.DarkGreen: return Color.FromArgb(70, 170, 95);
                default: return Theme.Text;
            }
        }

        private static readonly Regex RxVerified = new Regex(@"^\s+Verified:\s+(\d+)");
        private static readonly Regex RxUnknown = new Regex(@"^\s+Unknown:\s+(\d+)");
        private static readonly Regex RxCheck = new Regex(@"^\s+Check:\s+(\d+)");
        private static readonly Regex RxCheat = new Regex(@"^\s+Cheat:\s+(\d+)");
        private static readonly Regex RxSusp = new Regex(@"^\s+Suspicious:\s+(\d+)");

        private void ParseStats(string line)
        {
            Match m;
            if ((m = RxVerified.Match(line)).Success) _stats[0].Set(m.Groups[1].Value, Theme.Win);
            else if ((m = RxUnknown.Match(line)).Success) _stats[1].Set(m.Groups[1].Value, Theme.Gold);
            else if ((m = RxCheck.Match(line)).Success) _stats[2].Set(m.Groups[1].Value, Theme.Gold);
            else if ((m = RxCheat.Match(line)).Success) { int v = int.Parse(m.Groups[1].Value); _stats[3].Set(v.ToString(), v > 0 ? Theme.Loss : Theme.Win); if (_stats[2].Value == "-") _stats[2].Set("0", Theme.Win); }
            else if ((m = RxSusp.Match(line)).Success) { _suspicious += int.Parse(m.Groups[1].Value); _stats[4].Set(_suspicious.ToString(), _suspicious > 0 ? Theme.Loss : Theme.Win); }
            else if (line.Trim() == "Nothing suspicious found." && _stats[4].Value == "-") _stats[4].Set("0", Theme.Win);
        }

        private void ShowNotice()
        {
            Append("This is NOT a hacking or malware tool. It only READS, and only:", ConsoleColor.White, false);
            Append("  Mod Analyzer      fingerprints your mods and looks them up on Modrinth, Megabase and CurseForge,", ConsoleColor.Gray, false);
            Append("                    and searches unknown mods for known cheat names", ConsoleColor.Gray, false);
            Append("  Injection Checker reads how Minecraft was started, which DLLs are in it and its memory (read-only),", ConsoleColor.Gray, false);
            Append("                    then watches it live", ConsoleColor.Gray, false);
            Append("  System Check      reads Windows' records of programs that ran, cleared logs, USB drives,", ConsoleColor.Gray, false);
            Append("                    the Recycle Bin and Minecraft account NAMES", ConsoleColor.Gray, false);
            Append("", null, false);
            Append("It does NOT:", ConsoleColor.White, false);
            Append("  - upload your files, or send anything unless you press Send at the end", ConsoleColor.Gray, false);
            Append("  - read passwords, tokens, emails, browser data or Discord", ConsoleColor.Gray, false);
            Append("  - change the game's memory, or change, move or delete any files", ConsoleColor.Gray, false);
            Append("", null, false);
            Append("Source code: https://github.com/napatc1/ModAnzer", ConsoleColor.DarkGray, false);
            Append("", null, false);
            Append("Pick a check on the left and press Start scan.", ConsoleColor.Yellow, false);
        }

        // ------------------------------------------------------------------ admin

        private static bool IsAdmin()
        {
            try { return new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator); }
            catch { return false; }
        }

        private void RestartAsAdmin()
        {
            if (_running) return;
            try
            {
                Process.Start(new ProcessStartInfo(Application.ExecutablePath, "--mode " + _mode) { Verb = "runas", UseShellExecute = true });
                Close();
            }
            catch (Win32Exception)
            {
                // the person said no to the Windows prompt
            }
        }

        // ------------------------------------------------------------------ sending

        private static string ReadReportUrl()
        {
            var m = Regex.Match(ScanRunner.ScriptText(), @"\$ReportUrl\s*=\s*'([^']+)'");
            return m.Success ? m.Groups[1].Value : "";
        }

        private void SendReport()
        {
            string id = _discordId.Text.Trim();
            if (!Regex.IsMatch(id, @"^\d{17,20}$"))
            {
                _sendHint.ForeColor = Theme.Loss;
                _sendHint.Text = "A Discord ID is a long number, like 123456789012345678. Settings > Advanced > Developer Mode, then right-click your name > Copy User ID.";
                return;
            }
            if (_reportFile == null || string.IsNullOrEmpty(_reportUrl)) return;
            string report = File.ReadAllText(_reportFile, Encoding.UTF8).Trim();
            if (!report.StartsWith("{") || report.Length < 3) return;
            string body = "{\"report\":{\"discordId\":\"" + id + "\"," + report.Substring(1) + "}";

            _send.Enabled = false; _send.Text = "Sending";
            _sendHint.ForeColor = Theme.Muted; _sendHint.Text = "Sending...";
            string url = _reportUrl;
            Task.Run(() =>
            {
                try
                {
                    using (var wc = new WebClient())
                    {
                        wc.Encoding = Encoding.UTF8;
                        wc.Headers[HttpRequestHeader.ContentType] = "application/json; charset=utf-8";
                        wc.Headers[HttpRequestHeader.UserAgent] = "ModAnzer-App (github.com/napatc1/ModAnzer)";
                        wc.UploadString(url, "POST", body);
                    }
                    return (string)null;
                }
                catch (WebException ex)
                {
                    try
                    {
                        using (var r = new StreamReader(ex.Response.GetResponseStream()))
                        {
                            var m = Regex.Match(r.ReadToEnd(), "\"error\"\\s*:\\s*\"([^\"]+)\"");
                            if (m.Success) return m.Groups[1].Value;
                        }
                    }
                    catch { }
                    return ex.Message;
                }
                catch (Exception ex) { return ex.Message; }
            }).ContinueWith(t => BeginInvoke((Action)(() =>
            {
                if (t.Result == null)
                {
                    _send.Text = "Sent";
                    _sendHint.ForeColor = Theme.Win;
                    _sendHint.Text = "Sent. PrimeTiers staff will see the results in their mod-check channel.";
                }
                else
                {
                    _send.Enabled = true; _send.Text = "Retry";
                    _sendHint.ForeColor = Theme.Loss;
                    _sendHint.Text = "Could not send: " + t.Result + ". Take a screenshot of this window for staff instead.";
                }
            })));
        }

        // ------------------------------------------------------------------ misc

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (_runner != null && _running) _runner.Abort();
            base.OnFormClosing(e);
        }

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern IntPtr SendMessage(IntPtr hWnd, int msg, IntPtr wParam, IntPtr lParam);

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern IntPtr SendMessage(IntPtr hWnd, int msg, IntPtr wParam, string lParam);

        private static void SetCueBanner(TextBox box, string text)
        {
            SendMessage(box.Handle, 0x1501, new IntPtr(1), text); // EM_SETCUEBANNER
        }

        /// <summary>Fills the window with sample results (for the build's preview screenshot).</summary>
        internal void LoadDemo()
        {
            _out.Clear(); _line.Clear(); _lineStart = 0;
            Append("{ Mod folders found (3) }", ConsoleColor.DarkCyan, false);
            Append("> Modrinth     41 jars  %APPDATA%\\ModrinthApp\\profiles\\PvP\\mods", ConsoleColor.Gray, false);
            Append("> Dawn         12 jars  %APPDATA%\\.dawn\\profiles\\cpvp\\mods", ConsoleColor.Gray, false);
            Append("", null, false);
            Append("{ Modrinth  %APPDATA%\\ModrinthApp\\profiles\\PvP\\mods }", ConsoleColor.DarkCyan, false);
            Append("  [OK]    Sodium                          sodium-fabric-0.6.jar", ConsoleColor.Green, false);
            Append("  [OK]    Lithium                         lithium-fabric-0.14.jar", ConsoleColor.Green, false);
            Append("  [CF]    JourneyMap                      journeymap-1.21.jar", ConsoleColor.Cyan, false);
            Append("  [CHECK] physics-mod.jar", ConsoleColor.Yellow, false);
            Append("          common words only: Velocity", ConsoleColor.DarkYellow, false);
            Append("  [CHEAT] totally-legit.jar", ConsoleColor.Red, false);
            Append("          totally-legit.jar: AutoCrystal, TriggerBot", ConsoleColor.Magenta, false);
            Append("", null, false);
            Append("{ Summary }", ConsoleColor.DarkCyan, false);
            Append("  Verified: 51", ConsoleColor.Green, false);
            Append("  Unknown:  0", ConsoleColor.DarkYellow, false);
            Append("  Check:    1", ConsoleColor.Yellow, false);
            Append("  Cheat:    1", ConsoleColor.Red, false);
            Append("  Suspicious: 0", ConsoleColor.Gray, false);
            _reportFile = "demo";
            _send.Enabled = true;
            _start.Text = "Scan again";
            _sendHint.ForeColor = Theme.Text;
            _sendHint.Text = "Scan finished. Enter your Discord ID and press Send to share the results with PrimeTiers staff.";
        }
    }
}
