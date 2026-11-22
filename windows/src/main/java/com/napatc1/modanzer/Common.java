package com.napatc1.modanzer;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared bits: paths, alerts, launcher names, tool names. */
final class Common {
    private Common() {}

    /** One finding of the injection or system check. Level is Red or Yellow. */
    record Alert(String time, String level, String text) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("Time", time);
            m.put("Level", level);
            m.put("Text", text);
            return m;
        }
    }

    static Alert alert(Out out, String level, String text, List<Alert> into) {
        String stamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        out.progress("");
        out.text(Out.C.DIM, "  [" + stamp + "] ");
        out.line(level.equals("Red") ? Out.C.RED : level.equals("Yellow") ? Out.C.YELLOW : Out.C.GRAY, text);
        Alert a = new Alert(stamp, level, text);
        if (into != null) into.add(a);
        return a;
    }

    static List<Map<String, Object>> alertMaps(List<Alert> alerts) {
        List<Map<String, Object>> l = new ArrayList<>();
        for (Alert a : alerts) l.add(a.toMap());
        return l;
    }

    /** Replaces the user's folders with %APPDATA%, %LOCALAPPDATA% and ~ so reports don't show them. */
    static String shortPath(String p) {
        if (p == null) return "";
        String[][] pairs = { { Sys.appData(), "%APPDATA%" }, { Sys.localAppData(), "%LOCALAPPDATA%" }, { Sys.userHome(), "~" } };
        for (String[] pair : pairs) {
            if (!pair[0].isEmpty() && p.regionMatches(true, 0, pair[0], 0, pair[0].length())) return pair[1] + p.substring(pair[0].length());
        }
        return p;
    }

    static String uptime(java.time.Duration t) {
        return String.format("%dh %dm %ds", t.toHours(), t.toMinutesPart(), t.toSecondsPart());
    }

    private static final Pattern DOT_FOLDER = Pattern.compile("\\\\\\.([^\\\\]+)\\\\");

    static String launcherName(String path) {
        String l = path.replace('/', '\\').toLowerCase(Locale.ROOT);
        if (l.matches("(?s).*(curseforge|\\\\twitch\\\\).*")) return "CurseForge";
        if (l.matches("(?s).*(modrinthapp|theseus).*")) return "Modrinth";
        if (l.contains("prismlauncher")) return "Prism";
        if (l.contains("polymc")) return "PolyMC";
        if (l.contains("multimc")) return "MultiMC";
        if (l.contains("atlauncher")) return "ATLauncher";
        if (l.contains("gdlauncher")) return "GDLauncher";
        if (l.contains("\\.technic\\")) return "Technic";
        if (l.contains("\\.dawn\\") || l.contains("\\.feather\\")) return "Dawn";
        if (l.contains("\\.lunarclient\\")) return "Lunar";
        if (l.contains("fastclient")) return "Fast Client";
        if (l.contains("\\.tlauncher\\")) return "TLauncher";
        if (l.contains("\\.minecraft\\")) return "Minecraft";
        Matcher m = DOT_FOLDER.matcher(l);
        if (m.find()) return "." + m.group(1);
        return "Custom";
    }

    static boolean underPath(String file, List<String> roots) {
        String f = file.replace('/', '\\').toLowerCase(Locale.ROOT);
        for (String r : roots) {
            if (r == null || r.isEmpty()) continue;
            String rr = r.replace('/', '\\').toLowerCase(Locale.ROOT);
            while (rr.endsWith("\\")) rr = rr.substring(0, rr.length() - 1);
            if (f.startsWith(rr)) return true;
        }
        return false;
    }

    /** Folders that normal programs aren't run or loaded from. */
    static List<String> userDropFolders() {
        String h = Sys.userHome();
        List<String> l = new ArrayList<>();
        for (String s : new String[] { h + "\\Downloads", h + "\\Desktop", h + "\\Documents", Sys.env("TEMP"), Sys.localAppData() + "\\Temp",
                h + "\\Videos", h + "\\Music", h + "\\Pictures" }) {
            if (s != null && !s.startsWith("\\") && !l.contains(s)) l.add(s);
        }
        return l;
    }

    // Program names that point to cheating tools (red) or are worth a look (yellow).
    private static final Pattern TOOL_RED = Pattern.compile(
        "cheat\\s?engine|process\\s?hacker|system\\s?informer|x64dbg|x32dbg|ollydbg|dnspy|recaf|jd-gui|bytecode\\s?viewer|extreme\\s?injector|xenos|injector|dll\\s?inject|kdmapper|manual\\s?map|vape[ .\\-_]?(lite|v4|client)?\\.exe|doomsday");
    private static final Pattern TOOL_YELLOW = Pattern.compile(
        "autohotkey|\\.ahk$|tinytask|macro|procexp|process\\s?explorer|ghidra|ida64|ida\\.exe|wireshark|fiddler|cheat");

    static String toolLevel(String name) {
        if (name == null || name.isEmpty()) return null;
        String n = name.toLowerCase(Locale.ROOT);
        if (TOOL_RED.matcher(n).find()) return "Red";
        if (TOOL_YELLOW.matcher(n).find()) return "Yellow";
        return null;
    }

    static Path path(String p) { return new File(p).toPath(); }

    private static final Map<String, String> SIGNERS = new java.util.HashMap<>();

    /** Who signed a file (Windows only), or null when it is not validly signed. */
    static synchronized String signer(String file) {
        if (SIGNERS.containsKey(file)) return SIGNERS.get(file);
        String r = null;
        if (Sys.WINDOWS) {
            String out = Sys.powershell(30, "$s = Get-AuthenticodeSignature -LiteralPath '" + file.replace("'", "''")
                + "' -ErrorAction SilentlyContinue; if ($s -and $s.Status -eq 'Valid' -and $s.SignerCertificate) { $s.SignerCertificate.GetNameInfo('SimpleName', $false) }");
            if (out != null && !out.isBlank()) r = out.trim();
        }
        SIGNERS.put(file, r);
        return r;
    }

    static boolean isAdmin() {
        if (!Sys.WINDOWS) return false;
        try {
            Process p = new ProcessBuilder("net", "session").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
