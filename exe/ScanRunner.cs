using System;
using System.Collections.Generic;
using System.IO;
using System.Management.Automation;
using System.Management.Automation.Runspaces;
using System.Threading;

namespace ModAnzer
{
    /// <summary>
    /// Runs the embedded ModAnzer.ps1 inside this app with Windows PowerShell 5.1
    /// (no console window), passing options instead of asking questions.
    /// </summary>
    internal sealed class ScanRunner
    {
        public event Action<string, ConsoleColor?, bool> Output; // text, colour, noNewLine
        public event Action<int> Finished;                        // exit code

        public string StopFile { get; private set; }
        public string ReportFile { get; private set; }
        private PowerShell _ps;
        private Thread _thread;

        public static string ScriptText()
        {
            using (var s = typeof(ScanRunner).Assembly.GetManifestResourceStream("ModAnzer.ps1"))
            using (var r = new StreamReader(s)) return r.ReadToEnd();
        }

        public void Start(string mode, bool offline, string launcher = "all", string[] path = null)
        {
            string tmp = Path.Combine(Path.GetTempPath(), "ModAnzer-" + Guid.NewGuid().ToString("N").Substring(0, 8));
            StopFile = tmp + ".stop";
            ReportFile = tmp + ".json";

            var args = new Dictionary<string, object>
            {
                { "Mode", mode }, { "SkipIntro", true }, { "NoPause", true }, { "Launcher", string.IsNullOrEmpty(launcher) ? "all" : launcher },
                { "StopFile", StopFile }, { "ReportOut", ReportFile }
            };
            if (offline) args["Offline"] = true;
            if (path != null && path.Length > 0) args["Path"] = path;

            _thread = new Thread(() => Run(args)) { IsBackground = true, Name = "ModAnzer scan" };
            _thread.SetApartmentState(ApartmentState.STA); // Shell / COM lookups need STA
            _thread.Start();
        }

        private void Run(Dictionary<string, object> args)
        {
            int code = 0;
            try
            {
                using (var rs = RunspaceFactory.CreateRunspace())
                {
                    rs.ThreadOptions = PSThreadOptions.UseCurrentThread;
                    rs.Open();
                    using (_ps = PowerShell.Create())
                    {
                        _ps.Runspace = rs;
                        _ps.AddScript(ScriptText());
                        foreach (var kv in args) _ps.AddParameter(kv.Key, kv.Value);

                        _ps.Streams.Information.DataAdded += (s, e) =>
                        {
                            var rec = _ps.Streams.Information[e.Index];
                            var msg = rec.MessageData as HostInformationMessage;
                            if (msg != null) Emit(msg.Message, msg.ForegroundColor, msg.NoNewLine == true);
                            else Emit(Convert.ToString(rec.MessageData), null, false);
                        };
                        _ps.Streams.Warning.DataAdded += (s, e) => Emit("WARNING: " + _ps.Streams.Warning[e.Index].Message, ConsoleColor.Yellow, false);
                        _ps.Streams.Error.DataAdded += (s, e) =>
                        {
                            var err = _ps.Streams.Error[e.Index];
                            Emit("ERROR: " + err.Exception.Message, ConsoleColor.Red, false);
                        };

                        _ps.Invoke();
                        object last = rs.SessionStateProxy.GetVariable("LASTEXITCODE");
                        if (last is int) code = (int)last;
                    }
                }
            }
            catch (Exception ex)
            {
                Emit("ModAnzer stopped because of an error: " + ex.Message, ConsoleColor.Red, false);
                code = 2;
            }
            finally
            {
                _ps = null;
                try { if (File.Exists(StopFile)) File.Delete(StopFile); } catch { }
                var f = Finished;
                if (f != null) f(code);
            }
        }

        private void Emit(string text, ConsoleColor? color, bool noNewLine)
        {
            var o = Output;
            if (o != null) o(text ?? "", color, noNewLine);
        }

        /// <summary>Ends the live watch the normal way, so the summary still prints.</summary>
        public void RequestStop()
        {
            try { File.WriteAllText(StopFile, "stop"); } catch { }
        }

        /// <summary>Stops immediately (window closing).</summary>
        public void Abort()
        {
            RequestStop();
            var ps = _ps;
            if (ps != null) { try { ps.BeginStop(null, null); } catch { } }
        }
    }
}
