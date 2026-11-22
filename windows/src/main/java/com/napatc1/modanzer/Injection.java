package com.napatc1.modanzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Injection Checker: launch options, loaded DLLs, a read-only memory scan, then a live watch. */
final class Injection {
    private final Out out;
    private final Options opt;
    private final Report report;

    Injection(Out out, Options opt, Report report) {
        this.out = out;
        this.opt = opt;
        this.report = report;
    }

    record Game(int pid, String path, String commandLine, Instant started) {}

    private static final Pattern GAME_FILTER = Pattern.compile("minecraft|fabric|forge|quilt|lunar|feather|dawn|--gameDir|--accessToken", Pattern.CASE_INSENSITIVE);
    private static final Pattern GAME_DIR = Pattern.compile("--gameDir\\s+(?:\"([^\"]+)\"|(\\S+))");

    static List<Game> minecraftProcesses() {
        List<Game> l = new ArrayList<>();
        ProcessHandle.allProcesses().forEach(p -> {
            try {
                String cmd = p.info().command().orElse("");
                String lc = cmd.toLowerCase(Locale.ROOT);
                if (!(lc.endsWith("javaw.exe") || lc.endsWith("java.exe") || lc.endsWith("/java"))) return;
                String cl = p.info().commandLine().orElse("");
                if (!GAME_FILTER.matcher(cl).find()) return;
                l.add(new Game((int) p.pid(), cmd, cl, p.info().startInstant().orElse(null)));
            } catch (RuntimeException ignored) { }
        });
        return l;
    }

    static String gameDir(String cmd) {
        Matcher m = GAME_DIR.matcher(cmd);
        return m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)) : null;
    }

    // ---------------------------------------------------------------- launch flags

    static List<String> launchFlags(String cmd) {
        List<String> flags = new ArrayList<>();
        Matcher m = Pattern.compile("-javaagent:(?:\"([^\"]+)\"|(\\S+))").matcher(cmd);
        while (m.find()) flags.add("Java agent at launch: " + (m.group(1) != null ? m.group(1) : m.group(2)) + "   (normal for Lunar's own agents, suspicious otherwise)");
        m = Pattern.compile("-(agentpath|agentlib):(?:\"([^\"]+)\"|(\\S+))").matcher(cmd);
        while (m.find()) flags.add("Native agent at launch (-" + m.group(1) + "): " + (m.group(2) != null ? m.group(2) : m.group(3)));
        m = Pattern.compile("-D(fabric\\.addMods|fabric\\.loadMods|fml\\.coreMods\\.load|fml\\.modFolders|forge\\.mods|fml\\.mods)=(?:\"([^\"]+)\"|(\\S+))").matcher(cmd);
        while (m.find()) flags.add("Extra mods loaded from outside the mods folder (-D" + m.group(1) + "): " + (m.group(2) != null ? m.group(2) : m.group(3)));
        if (Pattern.compile("-Xrunjdwp|-Xdebug").matcher(cmd).find()) flags.add("Started with the Java debugger turned on (-Xdebug / -Xrunjdwp)");
        if (Pattern.compile("(^|\\s)(-noverify|-Xverify:none)(\\s|$)").matcher(cmd).find()) flags.add("Bytecode verifier turned off (-noverify) - often needed by injected clients");
        return flags;
    }

    // ---------------------------------------------------------------- DLL verdicts

    record Verdict(String level, String text) {}

    private static final Pattern GAME_NATIVE_NAME = Pattern.compile(
        "^(lib)?(lwjgl|glfw|openal|jemalloc|jinput|jnidispatch|sqlite|discord[-_]?(rpc|game-sdk)|flite|tinyfd|stb|opengl|opus|rnnoise|speex|lame|vosk)");
    private static final Pattern GAME_NATIVE_DIR = Pattern.compile("[\\\\/](lwjgl|natives|bin|\\.minecraft|jna|versions|libraries|voicechat|opus)[^\\\\/]*[\\\\/]");

    /** Classifies one DLL loaded into Minecraft. Returns null when it is a normal Windows, Java or game file. */
    static Verdict moduleVerdict(String file, String javaHome) {
        String name = Path.of(file).getFileName() == null ? file : Path.of(file).getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.matches("^(lib)?instrument\\.(dll|so|dylib)$"))
            return new Verdict("Red", "Java agent support (instrument) is loaded - a Java agent was added at launch or attached to the running game");
        if (name.matches("^(lib)?jdwp\\.(dll|so|dylib)$"))
            return new Verdict("Red", "Java debugger (JDWP) is loaded - lets other programs change the game while it runs");
        List<String> sysRoots = new ArrayList<>(List.of(Sys.env("SystemRoot"), Sys.env("windir"), Sys.env("ProgramFiles"), Sys.env("ProgramFiles(x86)"), "/usr/lib", "/lib", "/usr/lib64", "/lib64"));
        if (javaHome != null) sysRoots.add(javaHome);
        boolean inDrop = Common.underPath(file, Common.userDropFolders());
        boolean gameNative = GAME_NATIVE_NAME.matcher(name).find() && (GAME_NATIVE_DIR.matcher(file).find() || file.matches("(?s).*(lwjgl|jna|voicechat).*"));
        if (gameNative) return null;
        if (Common.underPath(file, sysRoots) && !inDrop) return null;
        String signer = Common.signer(file);
        if (inDrop) return new Verdict("Red", "loaded from a user folder (" + (signer != null ? "signed by " + signer : "not signed") + ")");
        if (signer == null) return new Verdict("Red", "not signed by any publisher");
        return new Verdict("Yellow", "signed by " + signer + " (overlays like Discord, OBS, NVIDIA or Steam look like this)");
    }

    private static Set<String> modJars(String gameDir) {
        Set<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (gameDir != null) {
            Path mods = ModAnalyzer.fix(gameDir).resolve("mods");
            if (Files.isDirectory(mods)) {
                try (Stream<Path> s = Files.walk(mods, 2)) {
                    s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar") && Files.isRegularFile(p)).forEach(p -> set.add(p.toString()));
                } catch (IOException | RuntimeException ignored) { }
            }
        }
        return set;
    }

    // ---------------------------------------------------------------- the check

    void run() {
        out.section("Injection Checker");
        List<Game> procs = minecraftProcesses();
        if (procs.isEmpty()) {
            out.line(Out.C.RED, "No running Minecraft found. Start the game, then run the Injection Checker again.");
            return;
        }
        Game p = procs.get(0);
        if (procs.size() > 1) {
            for (int n = 0; n < procs.size(); n++) {
                String gd = gameDir(procs.get(n).commandLine());
                out.line(Out.C.TEXT, String.format("  %d) PID %d  %s", n + 1, procs.get(n).pid(), gd == null ? "" : Common.shortPath(gd)));
            }
            out.line(Out.C.DIM, "  Checking the first one.");
        }

        String gameDir = gameDir(p.commandLine());
        String javaHome = null;
        if (!p.path().isEmpty()) {
            Path jp = Path.of(p.path()).getParent();
            if (jp != null && jp.getParent() != null) javaHome = jp.getParent().toString();
        }
        String up = p.started() == null ? "" : "up " + Common.uptime(Duration.between(p.started(), Instant.now()));
        out.line(Out.C.WHITE, "> Minecraft PID " + p.pid() + (up.isEmpty() ? "" : " - " + up));
        if (gameDir != null) out.line(Out.C.GRAY, "  game folder: " + Common.shortPath(gameDir));
        if (!p.path().isEmpty()) out.line(Out.C.GRAY, "  java: " + Common.shortPath(p.path()));
        out.line();

        List<Common.Alert> alerts = new ArrayList<>();

        // 1. launch options
        out.section("Launch options");
        List<String> flags = launchFlags(p.commandLine());
        if (flags.isEmpty()) out.line(Out.C.GREEN, "  Nothing unusual.");
        for (String f : flags) Common.alert(out, "Red", f, alerts);
        out.line();

        // 2. DLLs loaded right now
        out.section("Loaded DLLs");
        List<String> baseline = Native.modules(p.pid());
        if (baseline == null) {
            out.line(Out.C.RED, "  Could not read the loaded DLLs (the game may be running as administrator - run this as administrator too).");
            baseline = new ArrayList<>();
        } else {
            out.line(Out.C.DIM, "  " + baseline.size() + " loaded. Checking...");
            int odd = 0;
            for (String m : baseline) {
                Verdict v = moduleVerdict(m, javaHome);
                if (v != null) { odd++; Common.alert(out, v.level(), Common.shortPath(m) + " - " + v.text(), alerts); }
            }
            if (odd == 0) out.line(Out.C.GREEN, "  All are normal Windows, Java or game files.");
        }
        out.line();

        // 3. game memory
        out.section("Game memory");
        if (!Sys.WINDOWS) {
            out.line(Out.C.DIM, "  Skipped - only works on Windows.");
        } else if (!Native.available()) {
            out.line(Out.C.YELLOW, "  Skipped - could not load the memory reader (antivirus may have blocked it).");
        } else {
            out.line(Out.C.DIM, "  Reading Minecraft's memory (read-only) for cheat names and hidden programs. This can take a minute...");
            Native.ScanResult scan = Native.scan(p.pid(), Cheats.MEMORY_NEEDLES, 8L * 1024 * 1024 * 1024);
            if (scan.error != null) {
                out.line(Out.C.RED, "  Could not read the game's memory: " + scan.error + ". Run ModAnzer as administrator.");
            } else {
                out.line(Out.C.DIM, String.format("  Scanned %.1f GB in %d regions%s.", scan.bytesScanned / 1073741824.0, scan.regions, scan.truncated ? " (stopped at the 8 GB limit)" : ""));
                for (String img : scan.hiddenImages)
                    Common.alert(out, "Red", "Hidden program in Minecraft's memory at " + img + " - a DLL/EXE with no file behind it (how manual-mapped cheats hide)", alerts);
                for (String k : new TreeSet<>(scan.hits.keySet()))
                    Common.alert(out, "Red", "Cheat name in Minecraft's memory: " + k + " (" + scan.hits.get(k) + " times)", alerts);
                if (scan.hiddenImages.isEmpty() && scan.hits.isEmpty()) out.line(Out.C.GREEN, "  Nothing found.");
                else if (!scan.hits.isEmpty()) out.line(Out.C.DIM, "  Note: a name can also be in memory because it was typed or shown in chat - check the context.");
            }
        }
        out.line();

        // 4. live watch
        out.section("Live watch");
        out.line(Out.C.GRAY, "  Watching for anything injected into the game, mods added or removed, and new programs");
        out.line(Out.C.GRAY, "  started from Downloads/Desktop/Temp. Keep this window open during the fight.");
        out.line();
        liveWatch(p, gameDir, javaHome, baseline, alerts);

        Map<String, Object> inj = new LinkedHashMap<>();
        inj.put("Admin", Common.isAdmin());
        inj.put("Uptime", up.replaceFirst("^up ", ""));
        inj.put("GameFolder", gameDir == null ? "" : Common.shortPath(gameDir));
        inj.put("Alerts", Common.alertMaps(alerts));
        report.injection = inj;

        out.section("Injection Checker summary");
        long red = alerts.stream().filter(a -> a.level().equals("Red")).count();
        long yellow = alerts.stream().filter(a -> a.level().equals("Yellow")).count();
        if (alerts.isEmpty()) {
            out.line(Out.C.GREEN, "  Nothing suspicious found.");
        } else {
            out.line(red > 0 ? Out.C.RED : Out.C.GRAY, "  Suspicious: " + red);
            out.line(yellow > 0 ? Out.C.YELLOW : Out.C.GRAY, "  Worth a look: " + yellow);
            out.line();
            for (Common.Alert a : alerts) {
                out.text(Out.C.DIM, "  [" + a.time() + "] ");
                out.line(a.level().equals("Red") ? Out.C.RED : Out.C.YELLOW, a.text());
            }
        }
        out.stat("suspicious", (int) red);
        out.line();
        out.line(Out.C.DIM, "This catches injected DLLs, Java agents, swapped mods and many memory-only cheats. Kernel-level cheats,");
        out.line(Out.C.DIM, "or cheats that wipe their headers and strings, can still hide. Alerts are leads - check them by hand.");
    }

    private void liveWatch(Game p, String gameDir, String javaHome, List<String> baseline, List<Common.Alert> alerts) {
        Set<String> known = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        known.addAll(baseline);
        Set<String> jarsNow = modJars(gameDir);
        Set<Long> seenProcs = new HashSet<>();
        ProcessHandle.allProcesses().forEach(x -> seenProcs.add(x.pid()));
        List<String> drop = Common.userDropFolders();
        Instant start = Instant.now();
        int liveAlerts = 0;

        while (!opt.stop) {
            Duration elapsed = Duration.between(start, Instant.now());
            if (opt.liveSeconds > 0 && elapsed.getSeconds() >= opt.liveSeconds) break;

            if (!ProcessHandle.of(p.pid()).map(ProcessHandle::isAlive).orElse(false)) {
                Common.alert(out, "Yellow", "Minecraft was closed.", alerts);
                break;
            }

            List<String> now = Native.modules(p.pid());
            if (now != null) {
                Set<String> nowSet = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                for (String m : now) {
                    nowSet.add(m);
                    if (known.add(m)) {
                        Verdict v = moduleVerdict(m, javaHome);
                        if (v != null) { Common.alert(out, v.level(), "NEW DLL injected/loaded: " + Common.shortPath(m) + " - " + v.text(), alerts); liveAlerts++; }
                        else Common.alert(out, "Gray", "New DLL loaded: " + Common.shortPath(m) + " (normal file)", null);
                    }
                }
                for (String m : new ArrayList<>(known)) {
                    if (!nowSet.contains(m) && moduleVerdict(m, javaHome) != null) {
                        Common.alert(out, "Yellow", "DLL unloaded: " + Common.shortPath(m) + " (injectors sometimes unload to hide)", alerts);
                        liveAlerts++;
                        known.remove(m);
                    }
                }
            }

            Set<String> jarsNew = modJars(gameDir);
            for (String j : jarsNew) if (!jarsNow.contains(j)) { Common.alert(out, "Red", "Mod ADDED while the game is running: " + Common.shortPath(j), alerts); liveAlerts++; }
            for (String j : jarsNow) if (!jarsNew.contains(j)) { Common.alert(out, "Red", "Mod REMOVED while the game is running: " + Common.shortPath(j), alerts); liveAlerts++; }
            jarsNow = jarsNew;

            List<ProcessHandle> procs = ProcessHandle.allProcesses().toList();
            for (ProcessHandle x : procs) {
                if (seenProcs.add(x.pid())) {
                    String xp = x.info().command().orElse(null);
                    if (xp != null && Common.underPath(xp, drop)) {
                        Common.alert(out, "Red", "Program started from a user folder: " + Common.shortPath(xp) + " (PID " + x.pid() + ")", alerts);
                        liveAlerts++;
                    }
                }
            }

            out.progress(String.format("Watching PID %d - %02d:%02d:%02d - live alerts: %d", p.pid(), elapsed.toHours(), elapsed.toMinutesPart(), elapsed.toSecondsPart(), liveAlerts));
            try { Thread.sleep(1500); } catch (InterruptedException e) { break; }
        }
        out.progress("");
        out.line();
    }
}
