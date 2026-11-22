package com.napatc1.modanzer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** System Check: traces that point to cheating, read from Windows' own records. */
final class SystemCheck {
    private final Out out;
    private final Options opt;
    private final Report report;
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    SystemCheck(Out out, Options opt, Report report) {
        this.out = out;
        this.opt = opt;
        this.report = report;
    }

    private static LocalDateTime local(Instant i) { return LocalDateTime.ofInstant(i, ZoneId.systemDefault()); }

    void run() {
        out.section("System Check");
        if (!Sys.WINDOWS) { out.line(Out.C.RED, "  The System Check only works on Windows."); return; }
        boolean admin = Common.isAdmin();
        if (!admin) out.line(Out.C.YELLOW, "  Not running as administrator: execution history, prefetch and some logs will be skipped.");
        List<Common.Alert> alerts = new ArrayList<>();
        Instant now = Instant.now();
        Instant day = now.minus(Duration.ofHours(24));
        Instant week = now.minus(Duration.ofDays(7));
        List<String> drop = Common.userDropFolders();
        out.line();

        pcAndTools(alerts, now);
        loggingServices(alerts);
        if (true) bam(alerts, admin, day, drop);
        prefetch(alerts, admin, day);
        psHistory(alerts, now);
        eventLogs(alerts, admin, week);
        usb(alerts, day);
        deletedAndDownloaded(alerts, week);
        Set<String> names = accounts(alerts);

        Map<String, Object> sys = new LinkedHashMap<>();
        sys.put("Admin", admin);
        sys.put("Alerts", Common.alertMaps(alerts));
        report.system = sys;
        report.accounts = new ArrayList<>(names);

        out.section("System Check summary");
        long red = alerts.stream().filter(a -> a.level().equals("Red")).count();
        long yellow = alerts.stream().filter(a -> a.level().equals("Yellow")).count();
        if (red == 0 && yellow == 0) {
            out.line(Out.C.GREEN, "  Nothing suspicious found.");
        } else {
            out.line(red > 0 ? Out.C.RED : Out.C.GRAY, "  Suspicious: " + red);
            out.line(yellow > 0 ? Out.C.YELLOW : Out.C.GRAY, "  Worth a look: " + yellow);
            out.line();
            for (String lvl : new String[] { "Red", "Yellow" }) {
                for (Common.Alert a : alerts) {
                    if (!a.level().equals(lvl)) continue;
                    out.text(Out.C.DIM, "  [" + a.time() + "] ");
                    out.line(lvl.equals("Red") ? Out.C.RED : Out.C.YELLOW, a.text());
                }
            }
        }
        out.stat("suspicious", (int) red);
        out.line();
        out.line(Out.C.DIM, "These are traces, not proof: check each one with the player before deciding.");
        out.line();
    }

    // ---- 1. restart + running tools
    private void pcAndTools(List<Common.Alert> alerts, Instant now) {
        out.section("PC and running programs");
        String boot = Sys.powershell(20, "(Get-CimInstance Win32_OperatingSystem).LastBootUpTime.ToUniversalTime().ToString('o')");
        if (boot != null && !boot.isBlank()) {
            try {
                Instant b = OffsetDateTime.parse(boot.trim()).toInstant();
                Duration up = Duration.between(b, now);
                out.line(Out.C.GRAY, String.format("  PC started %s (%dh %dm ago)", local(b).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), up.toHours(), up.toMinutesPart()));
                if (up.toMinutes() < 20) Common.alert(out, "Yellow", "PC was restarted " + up.toMinutes() + " minutes ago - a restart clears some traces", alerts);
            } catch (RuntimeException ignored) { }
        }
        String hw = Sys.powershell(20, "$c = Get-CimInstance Win32_ComputerSystem; \"$($c.Manufacturer) $($c.Model)\"");
        if (hw != null && Pattern.compile("VMware|VirtualBox|QEMU|KVM|Xen|Parallels|Virtual Machine|Hyper-V|innotek").matcher(hw).find())
            Common.alert(out, "Yellow", "Running inside a virtual machine (" + hw.trim() + ")", alerts);
        String tl = Sys.run(30, "tasklist", "/v", "/fo", "csv", "/nh");
        if (tl != null) {
            for (String line : tl.split("\r?\n")) {
                List<String> c = csv(line);
                if (c.size() < 9) continue;
                String exe = c.get(0), title = c.get(c.size() - 1);
                String lvl = Common.toolLevel(exe);
                if (lvl == null && "Red".equals(Common.toolLevel(title))) lvl = "Red";
                if (lvl != null) {
                    String proc = exe.replaceFirst("(?i)\\.exe$", "");
                    boolean hasTitle = !title.isBlank() && !title.equalsIgnoreCase("N/A");
                    Common.alert(out, lvl, "Running now: " + proc + " (PID " + c.get(1) + ")" + (hasTitle ? " - '" + title + "'" : ""), alerts);
                }
            }
        }
        out.line();
    }

    private static List<String> csv(String line) {
        List<String> l = new ArrayList<>();
        Matcher m = Pattern.compile("\"((?:[^\"]|\"\")*)\"").matcher(line);
        while (m.find()) l.add(m.group(1).replace("\"\"", "\""));
        return l;
    }

    // ---- 2. services that record what ran
    private void loggingServices(List<Common.Alert> alerts) {
        out.section("Logging services");
        String[][] rules = {
            { "EventLog", "Red", "records Windows event logs" },
            { "SysMain", "Yellow", "keeps the Prefetch record of programs that ran (some people turn it off for speed)" },
            { "PcaSvc", "Yellow", "Program Compatibility Assistant, records programs that ran" },
            { "DiagTrack", "Yellow", "diagnostics service (often turned off by privacy tweaks)" } };
        int okSvc = 0;
        for (String[] r : rules) {
            String state = serviceState(r[0]);
            if (state != null && !state.equals("RUNNING")) Common.alert(out, r[1], r[0] + " is " + state.substring(0, 1) + state.substring(1).toLowerCase(Locale.ROOT) + " - it " + r[2], alerts);
            else okSvc++;
        }
        String bam = serviceState("bam");
        if (bam != null && !bam.equals("RUNNING")) Common.alert(out, "Red", "BAM is " + bam.substring(0, 1) + bam.substring(1).toLowerCase(Locale.ROOT) + " - it records which programs ran", alerts);
        if (okSvc == rules.length) out.line(Out.C.GREEN, "  All running.");
        out.line();
    }

    private static String serviceState(String name) {
        String o = Sys.run(15, "sc", "query", name);
        if (o == null) return null;
        Matcher m = Pattern.compile("STATE\\s*:\\s*\\d+\\s+(\\w+)").matcher(o);
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : null;
    }

    // ---- 3. BAM: programs that ran (needs admin)
    private void bam(List<Common.Alert> alerts, boolean admin, Instant day, List<String> drop) {
        out.section("Programs that ran in the last 24 hours (BAM)");
        if (!admin) { out.line(Out.C.DIM, "  Skipped - needs administrator."); out.line(); return; }
        if (!Native.available()) { out.line(Out.C.DIM, "  Skipped - could not load the helper."); out.line(); return; }
        Map<String, String> devMap = new HashMap<>();
        for (char d = 'A'; d <= 'Z'; d++) {
            String dev = Native.dosDevice(d + ":");
            if (dev != null) devMap.put(dev, d + ":");
        }
        record Entry(String path, Instant time) {}
        List<Entry> entries = new ArrayList<>();
        for (String root : new String[] { "HKLM\\SYSTEM\\CurrentControlSet\\Services\\bam\\State\\UserSettings", "HKLM\\SYSTEM\\CurrentControlSet\\Services\\bam\\UserSettings" }) {
            String o = Sys.run(60, "reg", "query", root, "/s");
            if (o == null) continue;
            Pattern line = Pattern.compile("^\\s+(.+?)\\s{4}REG_BINARY\\s{4}([0-9A-Fa-f]+)\\s*$");
            for (String l : o.split("\r?\n")) {
                Matcher m = line.matcher(l);
                if (!m.find() || m.group(2).length() < 16) continue;
                String hex = m.group(2);
                long ft = 0;
                for (int i = 7; i >= 0; i--) ft = ft << 8 | Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
                Instant t = Instant.ofEpochMilli(ft / 10000 - 11644473600000L);
                if (t.isBefore(day)) continue;
                String path = m.group(1);
                for (Map.Entry<String, String> dev : devMap.entrySet()) {
                    if (path.regionMatches(true, 0, dev.getKey() + "\\", 0, dev.getKey().length() + 1)) { path = dev.getValue() + path.substring(dev.getKey().length()); break; }
                }
                entries.add(new Entry(path, t));
            }
        }
        entries.sort((a, b) -> b.time().compareTo(a.time()));
        int flagged = 0;
        for (Entry e : entries) {
            String file = e.path().substring(e.path().lastIndexOf('\\') + 1);
            String when = local(e.time()).format(HM);
            boolean exists = e.path().matches("^[A-Za-z]:\\\\.*") && Files.exists(Path.of(e.path()));
            String lvl = Common.toolLevel(file);
            boolean inDrop = Common.underPath(e.path(), drop);
            if (lvl != null) { flagged++; Common.alert(out, lvl, "Ran at " + when + ": " + Common.shortPath(e.path()) + (exists ? "" : " (file is gone now)"), alerts); }
            else if (!exists && e.path().contains("\\Users\\")) { flagged++; Common.alert(out, "Red", "Ran at " + when + " and was DELETED afterwards: " + Common.shortPath(e.path()), alerts); }
            else if (inDrop && exists && Common.signer(e.path()) == null) { flagged++; Common.alert(out, "Yellow", "Unsigned program from a user folder ran at " + when + ": " + Common.shortPath(e.path()), alerts); }
        }
        out.line(flagged > 0 ? Out.C.GRAY : Out.C.GREEN, "  " + entries.size() + " programs ran, " + flagged + " flagged.");
        out.line();
    }

    // ---- 4. Prefetch (needs admin)
    private void prefetch(List<Common.Alert> alerts, boolean admin, Instant day) {
        out.section("Prefetch");
        if (!admin) { out.line(Out.C.DIM, "  Skipped - needs administrator."); out.line(); return; }
        Path dir = Path.of(Sys.env("SystemRoot"), "Prefetch");
        List<Path> pf = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.pf")) { for (Path p : ds) pf.add(p); } catch (IOException | RuntimeException ignored) { }
        if (pf.isEmpty()) {
            if ("RUNNING".equals(serviceState("SysMain"))) Common.alert(out, "Red", "Prefetch folder is empty although Prefetch is on - it was probably cleared", alerts);
            else out.line(Out.C.DIM, "  Empty (Prefetch is turned off).");
        } else {
            Instant oldest = Instant.MAX;
            for (Path p : pf) oldest = created(p).isBefore(oldest) ? created(p) : oldest;
            if (pf.size() < 40 && oldest.isAfter(day)) Common.alert(out, "Yellow", "Only " + pf.size() + " Prefetch files, all created in the last 24 hours - it may have been cleared", alerts);
            int recent = 0, hit = 0;
            for (Path p : pf) {
                Instant mod = modified(p);
                if (mod.isBefore(day)) continue;
                recent++;
                String exe = p.getFileName().toString().replaceFirst("\\.pf$", "").replaceFirst("-[0-9A-F]{8}$", "");
                String lvl = Common.toolLevel(exe);
                if (lvl != null) { hit++; Common.alert(out, lvl, "Prefetch: " + exe + " ran at " + local(mod).format(HM), alerts); }
            }
            out.line(hit > 0 ? Out.C.GRAY : Out.C.GREEN, "  " + recent + " programs ran in the last 24 hours, " + hit + " flagged.");
        }
        out.line();
    }

    private static Instant created(Path p) {
        try { return Files.readAttributes(p, BasicFileAttributes.class).creationTime().toInstant(); } catch (IOException e) { return Instant.now(); }
    }

    private static Instant modified(Path p) {
        try { return Files.getLastModifiedTime(p).toInstant(); } catch (IOException e) { return Instant.EPOCH; }
    }

    // ---- 5. PowerShell history
    private void psHistory(List<Common.Alert> alerts, Instant now) {
        out.section("PowerShell history");
        Path hist = Path.of(Sys.appData(), "Microsoft", "Windows", "PowerShell", "PSReadLine", "ConsoleHost_history.txt");
        if (!Files.isRegularFile(hist)) { out.line(Out.C.DIM, "  No PowerShell history file."); out.line(); return; }
        String[][] rules = {
            { "Clear-History|ConsoleHost_history|HistorySaveStyle", "Red", "cleared or turned off PowerShell history" },
            { "wevtutil(\\.exe)?\\s+(cl|clear-log)|Clear-EventLog|Remove-EventLog|Limit-EventLog", "Red", "cleared event logs" },
            { "fsutil(\\.exe)?\\s+usn\\s+deletejournal", "Red", "deleted the file change journal" },
            { "(Remove-Item|\\bdel\\b|\\berase\\b|\\brm\\b|\\brmdir\\b|\\brd\\b|\\bri\\b).*(\\.jar|[\\\\/]mods\\b|\\.minecraft|prefetch|\\.pf\\b|\\.dll\\b)", "Red", "deleted mods or evidence" },
            { "(Set|Add)-MpPreference.*Exclusion", "Red", "added an antivirus exclusion" },
            { "(Stop|Set)-Service.*(SysMain|EventLog|PcaSvc|DiagTrack|bam)|sc(\\.exe)?\\s+(stop|config)\\s+(SysMain|EventLog|PcaSvc|DiagTrack|bam)", "Red", "stopped a logging service" },
            { "reg(\\.exe)?\\s+delete.*(bam|UserAssist|ShellBag|Prefetch)|Remove-Item.*HKLM:.*bam", "Red", "deleted execution history from the registry" },
            { "(Invoke-WebRequest|\\biwr\\b|\\bcurl\\b|\\bwget\\b|Start-BitsTransfer|DownloadFile|Invoke-RestMethod|\\birm\\b).*\\.(jar|dll|exe)\\b", "Yellow", "downloaded a program or mod from the command line" } };
        Pattern[] res = new Pattern[rules.length];
        for (int i = 0; i < rules.length; i++) res[i] = Pattern.compile(rules[i][0], Pattern.CASE_INSENSITIVE);
        List<String> lines;
        try { lines = Files.readAllLines(hist, StandardCharsets.UTF_8); } catch (IOException | RuntimeException e) { lines = List.of(); }
        int hit = 0;
        for (String line : lines) {
            if (line.contains("ModAnzer")) continue;
            for (int i = 0; i < rules.length; i++) {
                if (res[i].matcher(line).find()) {
                    hit++;
                    String shown = line.trim();
                    if (shown.length() > 110) shown = shown.substring(0, 110) + "...";
                    Common.alert(out, rules[i][1], rules[i][2] + ": " + shown, alerts);
                    break;
                }
            }
        }
        long hours = Duration.between(modified(hist), now).toHours();
        out.line(hit > 0 ? Out.C.GRAY : Out.C.GREEN, "  " + lines.size() + " commands, " + hit + " flagged (last change " + hours + "h ago).");
        out.line();
    }

    // ---- 6. event logs
    private void eventLogs(List<Common.Alert> alerts, boolean admin, Instant week) {
        out.section("Event logs");
        Object[][] queries = {
            { "System", 104, "an event log was cleared", false },
            { "Security", 1102, "the Security log was cleared", true },
            { "Application", 3079, "the file change journal was deleted", false } };
        int ev = 0;
        for (Object[] q : queries) {
            if ((boolean) q[3] && !admin) continue;
            String o = Sys.run(60, "wevtutil", "qe", (String) q[0], "/q:*[System[(EventID=" + q[1] + ") and TimeCreated[timediff(@SystemTime) <= 604800000]]]", "/c:5", "/f:xml");
            if (o == null) continue;
            Matcher m = Pattern.compile("SystemTime='([^']+)'").matcher(o);
            while (m.find()) {
                ev++;
                String when = m.group(1);
                try { when = local(Instant.parse(when.length() > 24 ? when.substring(0, 23) + "Z" : when)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")); } catch (RuntimeException ignored) { }
                Common.alert(out, "Red", when + ": " + q[2] + " (" + q[0] + " event " + q[1] + ")", alerts);
            }
        }
        if (ev == 0) out.line(Out.C.GREEN, "  No log clearing in the last 7 days" + (admin ? "" : " (Security log skipped - needs administrator)") + ".");
        out.line();
    }

    // ---- 7. USB drives
    private void usb(List<Common.Alert> alerts, Instant day) {
        out.section("USB drives");
        String o = Sys.powershell(40, "Get-PnpDevice -InstanceId 'USBSTOR\\*' -ErrorAction SilentlyContinue | ForEach-Object { $d = $_; "
            + "$l = (Get-PnpDeviceProperty -InstanceId $d.InstanceId -KeyName 'DEVPKEY_Device_LastArrivalDate' -ErrorAction SilentlyContinue).Data; "
            + "\"$($d.Status)|$($d.FriendlyName)|$(if ($l) { $l.ToUniversalTime().ToString('o') })\" }");
        int usb = 0;
        if (o != null) {
            for (String line : o.split("\r?\n")) {
                String[] p = line.trim().split("\\|", -1);
                if (p.length < 3) continue;
                Instant last = null;
                try { if (!p[2].isEmpty()) last = Instant.parse(p[2]); } catch (RuntimeException ignored) { }
                if (p[0].equals("OK")) { usb++; Common.alert(out, "Yellow", "USB drive plugged in now: " + p[1], alerts); }
                else if (last != null && !last.isBefore(day)) { usb++; Common.alert(out, "Yellow", "USB drive was plugged in at " + local(last).format(HM) + ": " + p[1], alerts); }
            }
        }
        if (usb == 0) out.line(Out.C.GREEN, "  No USB drives in the last 24 hours.");
        out.line();
    }

    // ---- 8. Recycle Bin + recent downloads
    private void deletedAndDownloaded(List<Common.Alert> alerts, Instant week) {
        out.section("Deleted and downloaded files");
        int files = 0;
        Set<String> badExt = Set.of(".jar", ".dll", ".exe", ".ahk", ".pf");
        String drive = Sys.env("SystemDrive").isEmpty() ? "C:" : Sys.env("SystemDrive");
        Path bin = Path.of(drive + "\\$Recycle.Bin");
        try (DirectoryStream<Path> sids = Files.newDirectoryStream(bin)) {
            for (Path sid : sids) {
                try (DirectoryStream<Path> items = Files.newDirectoryStream(sid, "$I*")) {
                    for (Path info : items) {
                        try {
                            ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(info)).order(ByteOrder.LITTLE_ENDIAN);
                            long ver = b.getLong(0);
                            long ft = b.getLong(16);
                            String orig;
                            if (ver == 2) { int n = b.getInt(24); orig = new String(b.array(), 28, Math.max(0, (n - 1) * 2), StandardCharsets.UTF_16LE); }
                            else { orig = new String(b.array(), 24, Math.min(520, b.array().length - 24), StandardCharsets.UTF_16LE); int z = orig.indexOf('\0'); if (z >= 0) orig = orig.substring(0, z); }
                            Instant when = Instant.ofEpochMilli(ft / 10000 - 11644473600000L);
                            String leaf = orig.substring(orig.lastIndexOf('\\') + 1);
                            String ext = leaf.contains(".") ? leaf.substring(leaf.lastIndexOf('.')).toLowerCase(Locale.ROOT) : "";
                            if (!badExt.contains(ext) || when.isBefore(week)) continue;
                            files++;
                            String from = orig.contains("\\") ? orig.substring(0, orig.lastIndexOf('\\')) : "";
                            String lvl = Set.of(".jar", ".dll", ".pf").contains(ext) || "Red".equals(Common.toolLevel(leaf)) ? "Red" : "Yellow";
                            Common.alert(out, lvl, "In the Recycle Bin: " + leaf + (from.isEmpty() ? "" : " (from " + Common.shortPath(from) + ")") + ", deleted " + local(when).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), alerts);
                        } catch (IOException | RuntimeException ignored) { }
                    }
                } catch (IOException | RuntimeException ignored) { }
            }
        } catch (IOException | RuntimeException ignored) { }
        for (String dir : new String[] { Sys.userHome() + "\\Downloads", Sys.userHome() + "\\Desktop" }) {
            Path d = Path.of(dir);
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> s = Files.walk(d, 2)) {
                for (Path f : s.filter(Files::isRegularFile).toList()) {
                    String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!(n.endsWith(".jar") || n.endsWith(".dll") || n.endsWith(".exe") || n.endsWith(".ahk"))) continue;
                    Instant mod = modified(f);
                    if (mod.isBefore(week)) continue;
                    String lvl = Common.toolLevel(n);
                    if (lvl == null && (n.endsWith(".jar") || n.endsWith(".ahk"))) lvl = "Yellow";
                    if (lvl != null) { files++; Common.alert(out, lvl, "Recent file: " + Common.shortPath(f.toString()) + " (" + local(mod).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + ")", alerts); }
                }
            } catch (IOException | RuntimeException ignored) { }
        }
        if (files == 0) out.line(Out.C.GREEN, "  Nothing suspicious in the Recycle Bin, Downloads or Desktop.");
        out.line();
    }

    // ---- 9. Minecraft accounts (names only)
    private Set<String> accounts(List<Common.Alert> alerts) {
        out.section("Minecraft accounts used on this PC (names only)");
        Set<String> names = minecraftAccountNames();
        if (names.isEmpty()) out.line(Out.C.DIM, "  None found.");
        else {
            out.line(Out.C.GRAY, "  " + String.join(", ", names));
            if (names.size() > 1) Common.alert(out, "Yellow", names.size() + " different Minecraft accounts used on this PC: " + String.join(", ", names), alerts);
        }
        out.line();
        return names;
    }

    private static void profileNames(Object o, Set<String> into) {
        if (o instanceof List<?> l) { for (Object x : l) profileNames(x, into); return; }
        Map<String, Object> m = Json.obj(o);
        if (m == null) return;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            Map<String, Object> v = Json.obj(e.getValue());
            if ((e.getKey().equals("minecraftProfile") || e.getKey().equals("profile")) && v != null && Json.str(v.get("name")) != null) into.add(Json.str(v.get("name")));
            else if (v != null || e.getValue() instanceof List) profileNames(e.getValue(), into);
        }
    }

    /** Minecraft account NAMES from launcher files and game logs (never tokens or emails). */
    static Set<String> minecraftAccountNames() {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        String app = Sys.appData(), home = Sys.userHome();
        for (String af : new String[] { app + "\\.minecraft\\launcher_accounts.json", app + "\\.minecraft\\launcher_accounts_microsoft_store.json",
                app + "\\PrismLauncher\\accounts.json", app + "\\PolyMC\\accounts.json", home + "\\.lunarclient\\settings\\game\\accounts.json" }) {
            try { profileNames(Json.parse(Files.readString(ModAnalyzer.fix(af))), names); } catch (IOException | RuntimeException ignored) { }
        }
        List<Path> logs = new ArrayList<>();
        logs.add(ModAnalyzer.fix(app + "\\.minecraft\\logs\\latest.log"));
        logsIn(ModAnalyzer.fix(app + "\\ModrinthApp\\profiles"), "logs", "latest.log", logs);
        logsIn(ModAnalyzer.fix(app + "\\PrismLauncher\\instances"), ".minecraft\\logs", "latest.log", logs);
        logsIn(ModAnalyzer.fix(home + "\\curseforge\\minecraft\\Instances"), "logs", "latest.log", logs);
        logsIn(ModAnalyzer.fix(app + "\\.dawn"), "logs", "latest.log", logs);
        Path lunar = ModAnalyzer.fix(home + "\\.lunarclient\\logs\\game");
        if (Files.isDirectory(lunar)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(lunar, "*.log")) { List<Path> l = new ArrayList<>(); ds.forEach(l::add); l.sort((a, b) -> modified(b).compareTo(modified(a))); logs.addAll(l.subList(0, Math.min(10, l.size()))); } catch (IOException ignored) { }
        }
        Pattern user = Pattern.compile("Setting user:\\s*([A-Za-z0-9_]{3,16})");
        for (Path lf : logs) {
            try (java.io.InputStream in = Files.newInputStream(lf)) {
                byte[] buf = in.readNBytes(2 * 1024 * 1024);
                Matcher m = user.matcher(new String(buf, StandardCharsets.UTF_8));
                while (m.find()) names.add(m.group(1));
            } catch (IOException | RuntimeException ignored) { }
        }
        return names;
    }

    /** latest.log of each sub-folder of a root, newest first, at most 10. */
    private static void logsIn(Path root, String sub, String file, List<Path> into) {
        if (!Files.isDirectory(root)) return;
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path d : ds) {
                Path f = d.resolve(sub.replace('\\', java.io.File.separatorChar)).resolve(file);
                if (Files.isRegularFile(f)) found.add(f);
            }
        } catch (IOException | RuntimeException ignored) { }
        found.sort((a, b) -> modified(b).compareTo(modified(a)));
        into.addAll(found.subList(0, Math.min(10, found.size())));
    }
}
