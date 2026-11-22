package com.napatc1.modanzer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Mod Analyzer: finds every mods folder, names the mods online and searches unknown ones for cheat names. */
final class ModAnalyzer {
    static final String TRUSTED_URL = "https://raw.githubusercontent.com/PrimeTiers/ModAnzer/main/trusted.txt";
    static final String FLAGGED_URL = "https://raw.githubusercontent.com/PrimeTiers/ModAnzer/main/flagged.txt";
    static final String CURSEFORGE_PROXY = "https://reumqqmnuvabnpmbstnu.supabase.co/functions/v1/modanzer-cf";

    static final List<String> MOD_FOLDER_NAMES = List.of("mods", "user-mods");
    /** Dot-folders that are never Minecraft and are expensive to walk. */
    static final Set<String> SKIP_DIRS = Set.of(
        ".git", ".gradle", ".m2", ".npm", ".nuget", ".cargo", ".rustup", ".android", ".vscode", ".vscode-server", ".cache",
        ".docker", ".conda", ".ipython", ".dotnet", ".templateengine", "node_modules", "assets", "libraries", "versions", "logs",
        "crash-reports", "screenshots", "saves", "resourcepacks", "shaderpacks", "natives", "jre", "runtime", "java");
    static final int MAX_DEPTH = 6;

    /** Mods known to be safe even though their code contains a cheat-like name. Matched by exact SHA1. */
    private final Map<String, String> trusted = new HashMap<>(Map.of(
        "1807bd0592525fe40d0d0ee69d30b7486c48fdc7", "FastClient HUD 1.21.11",
        "34911f6d85906b40ca45e7750c65b713955451d2", "Dawn Client"));

    record Launcher(String name, List<String> roots) {}

    record Jar(String folder, Path file, String hash) {}

    private final Out out;
    private final Options opt;
    private final Report report;

    ModAnalyzer(Out out, Options opt, Report report) {
        this.out = out;
        this.opt = opt;
        this.report = report;
    }

    // ---------------------------------------------------------------- finding folders

    static List<Launcher> launchers() {
        String app = Sys.appData(), local = Sys.localAppData(), home = Sys.userHome();
        return List.of(
            new Launcher("Minecraft Launcher (.minecraft, Badlion, SKLauncher)", List.of(app + "\\.minecraft")),
            new Launcher("CurseForge", List.of(home + "\\curseforge\\minecraft\\Instances", home + "\\Documents\\curseforge\\minecraft\\Instances", home + "\\Twitch\\Minecraft\\Instances")),
            new Launcher("Modrinth App", List.of(app + "\\ModrinthApp", app + "\\com.modrinth.theseus")),
            new Launcher("Prism / MultiMC / PolyMC", List.of(app + "\\PrismLauncher", app + "\\PolyMC", local + "\\Programs\\PrismLauncher", local + "\\Programs\\MultiMC")),
            new Launcher("ATLauncher", List.of(app + "\\ATLauncher")),
            new Launcher("GDLauncher", List.of(app + "\\gdlauncher_next", app + "\\gdlauncher_carbon")),
            new Launcher("Lunar Client", List.of(home + "\\.lunarclient")),
            new Launcher("Dawn Client (formerly Feather)", List.of(app + "\\.dawn", home + "\\.dawn", app + "\\.feather")),
            new Launcher("Fast Client", List.of(app + "\\.fastclient", home + "\\.fastclient", app + "\\FastClient", local + "\\FastClient", local + "\\Programs\\FastClient")),
            new Launcher("TLauncher", List.of(app + "\\.tlauncher", app + "\\.minecraft")),
            new Launcher("Technic", List.of(app + "\\.technic")));
    }

    /** Windows-style path text to a path this OS understands. */
    static Path fix(String p) {
        return Path.of(Sys.WINDOWS ? p : p.replace('\\', '/'));
    }

    static void findModFolders(Path root, int depth, Set<String> into) {
        if (depth > MAX_DEPTH) return;
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path c : ds) children.add(c);
        } catch (IOException | RuntimeException e) {
            return;
        }
        for (Path dir : children) {
            Path fn = dir.getFileName();
            if (fn == null) continue;
            String name = fn.toString().toLowerCase(Locale.ROOT);
            BasicFileAttributes at;
            try { at = Files.readAttributes(dir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); } catch (IOException e) { continue; }
            if (!at.isDirectory() || at.isSymbolicLink() || at.isOther()) {
                if (!(at.isDirectory() && MOD_FOLDER_NAMES.contains(name) && !at.isSymbolicLink())) continue;
            }
            if (MOD_FOLDER_NAMES.contains(name)) { into.add(dir.toString()); continue; }
            if (SKIP_DIRS.contains(name)) continue;
            findModFolders(dir, depth + 1, into);
        }
    }

    private static final Pattern GAME_DIR = Pattern.compile("--gameDir\\s+(?:\"([^\"]+)\"|(\\S+))");

    record RunningGame(long pid, String dir, Instant started) {}

    static List<RunningGame> runningGameDirs() {
        List<RunningGame> l = new ArrayList<>();
        try {
            ProcessHandle.allProcesses().forEach(p -> {
                try {
                    String cmd = p.info().command().orElse("").toLowerCase(Locale.ROOT);
                    if (!(cmd.endsWith("javaw.exe") || cmd.endsWith("java.exe") || cmd.endsWith("/java"))) return;
                    Matcher m = GAME_DIR.matcher(p.info().commandLine().orElse(""));
                    if (m.find()) l.add(new RunningGame(p.pid(), m.group(1) != null ? m.group(1) : m.group(2), p.info().startInstant().orElse(null)));
                } catch (RuntimeException ignored) { }
            });
        } catch (RuntimeException ignored) { }
        return l;
    }

    private static boolean hasJars(Path dir) {
        try (Stream<Path> s = Files.walk(dir, 2)) {
            return s.anyMatch(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar") && Files.isRegularFile(p));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private void addFromRoots(Iterable<String> roots, Set<String> folders) {
        for (String r : roots) {
            if (r == null || r.isBlank()) continue;
            Path root = fix(r);
            if (!Files.isDirectory(root)) continue;
            Path fn = root.getFileName();
            if (fn != null && MOD_FOLDER_NAMES.contains(fn.toString().toLowerCase(Locale.ROOT))) { folders.add(root.toString()); continue; }
            findModFolders(root, 0, folders);
        }
    }

    /** A folder the person typed or picked: use it if it holds jars, otherwise look for mods folders inside it. */
    private boolean addUserFolder(String typed, Set<String> folders) {
        String p = typed.trim();
        if (p.length() > 1 && (p.startsWith("\"") || p.startsWith("'"))) p = p.substring(1, p.length() - 1);
        Path dir = fix(p);
        if (!Files.isDirectory(dir)) return false;
        int before = folders.size();
        if (hasJars(dir)) folders.add(dir.toString());
        findModFolders(dir, 0, folders);
        if (folders.size() == before) folders.add(dir.toString());
        return true;
    }

    // ---------------------------------------------------------------- the scan

    void run() {
        List<RunningGame> running = runningGameDirs();
        if (!running.isEmpty()) {
            out.section("Running Minecraft");
            for (RunningGame r : running) {
                String up = r.started() == null ? "" : " - up " + Common.uptime(Duration.between(r.started(), Instant.now()));
                out.line(Out.C.WHITE, "> PID " + r.pid() + up);
                out.line(Out.C.GRAY, "  " + Common.shortPath(r.dir()));
            }
            out.line();
        }

        Set<String> folders = new LinkedHashSet<>();
        List<Launcher> launchers = launchers();
        if (!opt.path.isEmpty()) {
            for (String p : opt.path) if (!addUserFolder(p, folders)) out.line(Out.C.RED, "Not a folder: " + p);
        } else {
            int choice = 1;
            if (opt.launcher != null && !opt.launcher.isEmpty() && !opt.launcher.equalsIgnoreCase("all")) {
                for (int n = 0; n < launchers.size(); n++) {
                    if (launchers.get(n).name().toLowerCase(Locale.ROOT).contains(opt.launcher.toLowerCase(Locale.ROOT))) { choice = n + 2; break; }
                }
            }
            if (choice >= 2) {
                Launcher pick = launchers.get(choice - 2);
                out.progress("Looking for " + pick.name() + " mods folders...");
                addFromRoots(pick.roots(), folders);
                if (folders.isEmpty()) {
                    out.progress("");
                    out.line(Out.C.RED, "No " + pick.name() + " mods folder found in the usual place.");
                    out.line(Out.C.DIM, "Scanning everything instead.");
                    choice = 1;
                }
            }
            if (choice == 1) {
                out.progress("Looking for mods folders...");
                List<String> all = new ArrayList<>();
                for (Launcher l : launchers) all.addAll(l.roots());
                // Microsoft Store launcher
                Path pk = fix(Sys.localAppData() + "\\Packages");
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(pk, "Microsoft.4297127D64EC6_*")) {
                    for (Path d : ds) all.add(d.resolve("LocalCache").resolve("Roaming").resolve(".minecraft").toString());
                } catch (IOException | RuntimeException ignored) { }
                // any dot-folder in AppData\Roaming or the user folder (.dawn, .badlion, other clients)
                for (String base : new String[] { Sys.appData(), Sys.userHome() }) {
                    if (base.isEmpty()) continue;
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(fix(base))) {
                        for (Path d : ds) {
                            String n = d.getFileName().toString();
                            if (n.startsWith(".") && !SKIP_DIRS.contains(n.toLowerCase(Locale.ROOT)) && Files.isDirectory(d)) all.add(d.toString());
                        }
                    } catch (IOException | RuntimeException ignored) { }
                }
                for (RunningGame r : running) all.add(r.dir());
                addFromRoots(new LinkedHashSet<>(all), folders);
            }
        }

        // jars (also one level of subfolders, e.g. mods\1.21\)
        List<Jar> jars = new ArrayList<>();
        for (String f : folders) {
            try (Stream<Path> s = Files.walk(fix(f), 2)) {
                s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar") && Files.isRegularFile(p)).sorted()
                    .forEach(p -> jars.add(new Jar(f, p, null)));
            } catch (IOException | RuntimeException ignored) { }
        }
        out.progress("");
        List<String> withJars = new ArrayList<>(new LinkedHashSet<>(jars.stream().map(Jar::folder).toList()));
        if (withJars.isEmpty()) {
            out.line(Out.C.RED, "No mods found.");
            out.line(Out.C.DIM, "Pick \"Other folder\" to choose your mods folder yourself.");
            return;
        }

        out.section("Mod folders found (" + withJars.size() + ")");
        for (String f : withJars) {
            long count = jars.stream().filter(j -> j.folder().equals(f)).count();
            out.text(Out.C.WHITE, String.format("> %-11s", Common.launcherName(f)));
            out.text(Out.C.DIM, String.format("%4d jars  ", count));
            out.line(Out.C.GRAY, Common.shortPath(f));
        }
        out.line();

        // ---- hash
        List<Jar> hashed = new ArrayList<>();
        int i = 0;
        for (Jar j : jars) {
            i++;
            out.progress("Hashing " + i + " / " + jars.size());
            hashed.add(new Jar(j.folder(), j.file(), sha1(j.file())));
        }
        List<String> hashes = new ArrayList<>(new LinkedHashSet<>(hashed.stream().map(Jar::hash).filter(h -> h != null).toList()));
        Map<String, Jar> jarByHash = new HashMap<>();
        for (Jar j : hashed) if (j.hash() != null) jarByHash.putIfAbsent(j.hash(), j);

        // ---- look up
        Map<String, String> known = new HashMap<>();
        Map<String, String> slugByHash = new HashMap<>();
        if (!opt.offline && !hashes.isEmpty()) {
            lookupModrinth(hashes, known, slugByHash);
            List<String> left = hashes.stream().filter(h -> !known.containsKey(h)).toList();
            int n = 0;
            for (String h : left) {
                n++;
                out.progress("Checking Megabase " + n + " / " + left.size());
                try {
                    Map<String, Object> r = Json.obj(Json.parse(Sys.get("https://megabase.vercel.app/api/query?hash=" + h, 20)));
                    if (r != null && r.get("error") == null && Json.obj(r.get("data")) != null && Json.str(Json.obj(r.get("data")).get("name")) != null) {
                        known.put(h, Json.str(Json.obj(r.get("data")).get("name")));
                    }
                } catch (IOException | InterruptedException | RuntimeException ignored) { }
            }
        }
        if (!opt.offline) {
            try {
                for (String line : Sys.get(TRUSTED_URL, 15).split("\n")) {
                    Matcher m = Pattern.compile("^\\s*([0-9a-fA-F]{40})\\s+(.+?)\\s*$").matcher(line);
                    if (m.find()) trusted.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2));
                }
            } catch (IOException | InterruptedException | RuntimeException ignored) { }
        }
        for (String h : hashes) if (trusted.containsKey(h)) known.put(h, trusted.get(h) + " (trusted)");

        if (!opt.offline) lookupCurseForge(hashes, jarByHash, known);

        // ---- cheats among known mods
        Map<String, String> flagged = new HashMap<>();
        Map<String, String> flaggedSlugs = new HashMap<>(), flaggedHashes = new HashMap<>();
        if (!opt.offline) {
            try {
                for (String raw : Sys.get(FLAGGED_URL, 15).split("\n")) {
                    String line = raw.replaceAll("#.*$", "").trim();
                    Matcher hm = Pattern.compile("^([0-9a-fA-F]{40})(?:\\s+(.+))?$").matcher(line);
                    Matcher sm = Pattern.compile("^([A-Za-z0-9_.-]+)(?:\\s+(.+))?$").matcher(line);
                    if (hm.find()) flaggedHashes.put(hm.group(1).toLowerCase(Locale.ROOT), hm.group(2) == null ? "" : hm.group(2));
                    else if (sm.find()) flaggedSlugs.put(sm.group(1).toLowerCase(Locale.ROOT), sm.group(2) == null ? "" : sm.group(2));
                }
            } catch (IOException | InterruptedException | RuntimeException ignored) { }
        }
        List<String> knownHashes = hashes.stream().filter(h -> known.containsKey(h) && !trusted.containsKey(h)).toList();
        i = 0;
        for (String h : knownHashes) {
            i++;
            out.progress("Checking known mods for cheats " + i + " / " + knownHashes.size());
            String slug = slugByHash.getOrDefault(h, "");
            if (flaggedHashes.containsKey(h)) { flagged.put(h, ("on the flagged list " + flaggedHashes.get(h)).trim()); continue; }
            if (!slug.isEmpty() && flaggedSlugs.containsKey(slug.toLowerCase(Locale.ROOT))) {
                flagged.put(h, ("flagged Modrinth project '" + slug + "' " + flaggedSlugs.get(slug.toLowerCase(Locale.ROOT))).trim());
                continue;
            }
            Matcher nm = Cheats.FLAGGED_NAME_RE.matcher(known.get(h) + " " + slug);
            if (nm.find()) { flagged.put(h, "name says '" + nm.group().trim() + "'"); continue; }
            try {
                List<String> found = Cheats.cheatClasses(Files.readAllBytes(jarByHash.get(h).file()));
                if (!found.isEmpty()) flagged.put(h, "has cheat modules: " + String.join(", ", found));
            } catch (IOException | RuntimeException ignored) { }
        }

        // ---- scan unknown jars
        Map<String, List<Cheats.Hit>> scanned = new HashMap<>();
        List<String> unknownHashes = hashes.stream().filter(h -> !known.containsKey(h)).toList();
        i = 0;
        for (String h : unknownHashes) {
            i++;
            out.progress("Scanning unknown mods for cheat strings " + i + " / " + unknownHashes.size());
            Path file = jarByHash.get(h).file();
            List<Cheats.Hit> hits = new ArrayList<>();
            try {
                Cheats.searchJar(Files.readAllBytes(file), file.getFileName().toString(), hits, 0);
            } catch (IOException | RuntimeException e) {
                hits.add(new Cheats.Hit(file.getFileName().toString(), List.of("could not open: " + e.getMessage()), false));
            }
            scanned.put(h, hits);
        }
        out.progress("");

        printResults(hashed, withJars, known, flagged, scanned);
    }

    private void lookupModrinth(List<String> hashes, Map<String, String> known, Map<String, String> slugByHash) {
        out.progress("Checking " + hashes.size() + " unique mods on Modrinth...");
        try {
            Map<String, String> projectByHash = new HashMap<>();
            for (int n = 0; n < hashes.size(); n += 500) {
                List<String> chunk = hashes.subList(n, Math.min(n + 500, hashes.size()));
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("hashes", chunk);
                body.put("algorithm", "sha1");
                Map<String, Object> resp = Json.obj(Json.parse(Sys.postJson("https://api.modrinth.com/v2/version_files", Json.write(body), 20)));
                if (resp == null) continue;
                for (Map.Entry<String, Object> e : resp.entrySet()) {
                    Map<String, Object> v = Json.obj(e.getValue());
                    if (v != null && Json.str(v.get("project_id")) != null) projectByHash.put(e.getKey().toLowerCase(Locale.ROOT), Json.str(v.get("project_id")));
                }
            }
            List<String> ids = new ArrayList<>(new LinkedHashSet<>(projectByHash.values()));
            Map<String, String> titleById = new HashMap<>(), slugById = new HashMap<>();
            for (int n = 0; n < ids.size(); n += 100) {
                String q = java.net.URLEncoder.encode(Json.write(ids.subList(n, Math.min(n + 100, ids.size()))), java.nio.charset.StandardCharsets.UTF_8);
                for (Object o : Json.list(Json.parse(Sys.get("https://api.modrinth.com/v2/projects?ids=" + q, 20)))) {
                    Map<String, Object> p = Json.obj(o);
                    if (p == null) continue;
                    titleById.put(Json.str(p.get("id")), Json.str(p.get("title")));
                    slugById.put(Json.str(p.get("id")), Json.str(p.get("slug")));
                }
            }
            for (Map.Entry<String, String> e : projectByHash.entrySet()) {
                String t = titleById.get(e.getValue());
                known.put(e.getKey(), t == null ? "(Modrinth project)" : t);
                if (slugById.get(e.getValue()) != null) slugByHash.put(e.getKey(), slugById.get(e.getValue()));
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            out.progress("");
            out.line(Out.C.RED, "[!] Modrinth lookup failed: " + e.getMessage());
        }
    }

    /** CurseForge by fingerprint, through ModAnzer's small server that holds the API key. */
    private void lookupCurseForge(List<String> hashes, Map<String, Jar> jarByHash, Map<String, String> known) {
        List<String> left = hashes.stream().filter(h -> !known.containsKey(h)).toList();
        if (left.isEmpty()) return;
        Map<String, String> hashByFp = new LinkedHashMap<>();
        int i = 0;
        for (String h : left) {
            i++;
            out.progress("CurseForge fingerprint " + i + " / " + left.size());
            try { hashByFp.put(String.valueOf(Cheats.curseForgeFingerprint(Files.readAllBytes(jarByHash.get(h).file()))), h); } catch (IOException | RuntimeException ignored) { }
        }
        out.progress("Checking " + hashByFp.size() + " mods on CurseForge...");
        try {
            List<String> fps = new ArrayList<>(hashByFp.keySet());
            for (int n = 0; n < fps.size(); n += 1000) {
                List<Long> chunk = fps.subList(n, Math.min(n + 1000, fps.size())).stream().map(Long::valueOf).toList();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("fingerprints", chunk);
                Map<String, Object> r = Json.obj(Json.parse(Sys.postJson(CURSEFORGE_PROXY, Json.write(body), 30)));
                Map<String, Object> matches = r == null ? null : Json.obj(r.get("matches"));
                if (matches == null) continue;
                for (Map.Entry<String, Object> e : matches.entrySet()) {
                    String h = hashByFp.get(e.getKey());
                    if (h == null) continue;
                    Map<String, Object> m = Json.obj(e.getValue());
                    String name = m == null ? null : Json.str(m.get("name"));
                    known.put(h, (name == null || name.isEmpty() ? "(CurseForge project)" : name) + " [CurseForge]");
                }
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            out.progress("");
            out.line(Out.C.RED, "[!] CurseForge lookup failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- the report

    /** "Launcher > instance" for a mods folder, e.g. "Modrinth > Atzlk_s PMW". */
    static String instanceLabel(String modsFolder) {
        String launcher = Common.launcherName(modsFolder);
        Path parent = fix(modsFolder).getParent();
        String inst = parent == null || parent.getFileName() == null ? "" : parent.getFileName().toString();
        if (inst.equals(".minecraft") || inst.equals("minecraft") || inst.isEmpty()) {
            Path up = parent == null ? null : parent.getParent();
            String u = up == null || up.getFileName() == null ? "" : up.getFileName().toString();
            inst = !u.isEmpty() && !u.equals("Roaming") && !u.equals("AppData") && !u.startsWith(".") ? u : null;
        }
        if (inst != null && inst.startsWith(".")) inst = null;
        if (inst != null && !inst.equals(launcher) && !("." + inst).equals(launcher)) return launcher + " > " + inst;
        return launcher;
    }

    private record CfRecord(String name, Long length) {}

    /** Mods the CurseForge app recorded as installed (minecraftinstance.json next to the mods folder). */
    private static Map<String, CfRecord> curseForgeRecords(String modsFolder) {
        Map<String, CfRecord> map = new HashMap<>();
        Path parent = fix(modsFolder).getParent();
        if (parent == null) return map;
        Path json = parent.resolve("minecraftinstance.json");
        if (!Files.isRegularFile(json)) return map;
        try {
            Map<String, Object> inst = Json.obj(Json.parse(Files.readString(json)));
            if (inst == null) return map;
            for (Object a : Json.list(inst.get("installedAddons"))) {
                Map<String, Object> ao = Json.obj(a);
                Map<String, Object> f = ao == null ? null : Json.obj(ao.get("installedFile"));
                if (f == null || Json.str(f.get("fileName")) == null || Json.str(ao.get("name")) == null) continue;
                Long len = f.get("fileLength") instanceof Number n ? n.longValue() : null;
                map.put(Json.str(f.get("fileName")), new CfRecord(Json.str(ao.get("name")), len));
            }
        } catch (IOException | RuntimeException ignored) { }
        return map;
    }

    private static String zoneHost(Path file) {
        if (!Sys.WINDOWS) return null;
        try (InputStream in = new java.io.FileInputStream(file.toString() + ":Zone.Identifier")) {
            Matcher m = Pattern.compile("HostUrl=(.+)").matcher(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            return m.find() ? m.group(1).trim() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Map<String, Object> entry(String status, String name, String title, String where) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Status", status);
        m.put("Name", name);
        m.put("Title", title);
        m.put("Where", where);
        return m;
    }

    private static Map<String, Object> nameWhere(String name, String where) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Name", name);
        m.put("Where", where);
        return m;
    }

    private static Map<String, Object> cheatEntry(String name, String where, String strings) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Name", name);
        m.put("Where", where);
        m.put("Strings", strings);
        return m;
    }

    private void printResults(List<Jar> jars, List<String> withJars, Map<String, String> known, Map<String, String> flagged, Map<String, List<Cheats.Hit>> scanned) {
        int totalCheat = 0, totalUnknown = 0, totalVerified = 0, totalCf = 0, totalCheck = 0;
        List<Map<String, Object>> cheatList = new ArrayList<>(), unknownList = new ArrayList<>(), checkList = new ArrayList<>(), allList = new ArrayList<>();

        for (String f : withJars) {
            out.section(Common.launcherName(f) + "  " + Common.shortPath(f));
            Map<String, CfRecord> cf = curseForgeRecords(f);
            List<Jar> here = jars.stream().filter(j -> j.folder().equals(f)).sorted((a, b) -> a.file().getFileName().toString().compareToIgnoreCase(b.file().getFileName().toString())).toList();
            String where = instanceLabel(f);
            for (Jar j : here) {
                String name = j.file().getFileName().toString();
                String h = j.hash();
                if (h != null && flagged.containsKey(h)) {
                    // Published on Modrinth/Megabase/CurseForge, but it is a cheat or PvP macro.
                    totalCheat++;
                    String why = known.get(h) + ": " + flagged.get(h);
                    allList.add(entry("CHEAT", name, why, where));
                    out.line(Out.C.RED, "  [CHEAT] " + name);
                    out.line(Out.C.MAGENTA, "          known as " + known.get(h) + ", but: " + flagged.get(h));
                    out.line(Out.C.DIM, "          sha1: " + h);
                    cheatList.add(cheatEntry(name, where, why));
                    continue;
                }
                if (h != null && known.containsKey(h)) {
                    totalVerified++;
                    allList.add(entry("OK", name, known.get(h), where));
                    out.text(Out.C.GREEN, String.format("  [OK]    %-32s", known.get(h)));
                    out.line(Out.C.DIM, name);
                    continue;
                }
                List<Cheats.Hit> hits = h == null ? null : scanned.get(h);
                boolean any = hits != null && !hits.isEmpty();
                boolean strong = any && hits.stream().anyMatch(Cheats.Hit::strong);
                String client = any && !strong ? Cheats.knownClient(name, j.file().toString()) : null;
                TreeSet<String> allStrings = new TreeSet<>();
                if (any) for (Cheats.Hit hit : hits) allStrings.addAll(hit.strings());
                if (client != null) {
                    // Legit client file; only common words like "Velocity" matched.
                    totalVerified++;
                    allList.add(entry("OK", name, client, where));
                    out.text(Out.C.GREEN, String.format("  [OK]    %-32s", client));
                    out.line(Out.C.DIM, name);
                } else if (any && !strong) {
                    // Only common words (Velocity, Hitboxes...): a lead, not proof.
                    totalCheck++;
                    String s = String.join(", ", allStrings);
                    allList.add(entry("CHECK", name, "common words: " + s, where));
                    out.line(Out.C.YELLOW, "  [CHECK] " + name);
                    out.text(Out.C.GRAY, "          common words only: ");
                    out.line(Out.C.DARKYELLOW, s);
                    out.line(Out.C.DIM, "          sha1: " + h);
                    checkList.add(cheatEntry(name, where, s));
                } else if (any) {
                    totalCheat++;
                    String s = String.join(", ", allStrings);
                    allList.add(entry("CHEAT", name, s, where));
                    out.line(Out.C.RED, "  [CHEAT] " + name);
                    for (Cheats.Hit hit : hits) {
                        out.text(Out.C.GRAY, "          " + hit.where() + ": ");
                        out.line(Out.C.MAGENTA, String.join(", ", hit.strings()));
                    }
                    out.line(Out.C.DIM, "          sha1: " + h);
                    cheatList.add(cheatEntry(name, where, s));
                } else if (cf.containsKey(name) && (cf.get(name).length() == null || cf.get(name).length() == fileSize(j.file()))) {
                    // Installed by the CurseForge app and no cheat names inside.
                    totalCf++;
                    allList.add(entry("CF", name, cf.get(name).name(), where));
                    out.text(Out.C.CYAN, String.format("  [CF]    %-32s", cf.get(name).name()));
                    out.line(Out.C.DIM, name);
                } else {
                    totalUnknown++;
                    allList.add(entry("UNKNOWN", name, "", where));
                    unknownList.add(cheatEntry(name, where, ""));
                    out.line(Out.C.DARKYELLOW, "  [?]     " + name);
                    String zone = zoneHost(j.file());
                    if (zone != null) out.line(Out.C.DIM, "          downloaded from " + zone);
                }
            }
            out.line();
        }

        out.section("Summary");
        out.line(Out.C.GREEN, "  Verified: " + totalVerified);
        if (totalCf > 0) out.line(Out.C.CYAN, "  CF app:   " + totalCf + "  (installed by the CurseForge app, no cheat names found)");
        out.line(Out.C.DARKYELLOW, "  Unknown:  " + totalUnknown);
        if (totalCheck > 0) out.line(Out.C.YELLOW, "  Check:    " + totalCheck + "  (only common words like Velocity - look at these by hand)");
        out.line(totalCheat > 0 ? Out.C.RED : Out.C.GRAY, "  Cheat:    " + totalCheat);
        if (opt.offline) out.line(Out.C.DIM, "  (offline mode - no online lookups were made)");
        out.stat("verified", totalVerified + totalCf);
        out.stat("unknown", totalUnknown);
        out.stat("check", totalCheck);
        out.stat("cheat", totalCheat);

        printGroups("Cheats found", "[CHEAT]", cheatList, Out.C.RED, Out.C.MAGENTA);
        printGroups("Worth a look", "[CHECK]", checkList, Out.C.YELLOW, Out.C.DARKYELLOW);
        out.line();
        out.line(Out.C.DIM, "Unknown does not mean cheat - it just is not on Modrinth/Megabase/CurseForge. CHECK means only common words");
        out.line(Out.C.DIM, "like Velocity matched. Cheat hits are clear cheat names. Check them all by hand.");

        List<String> folderLabels = new ArrayList<>();
        for (String f : withJars) folderLabels.add(instanceLabel(f));
        List<Map<String, Object>> unknowns = new ArrayList<>();
        for (Map<String, Object> c : checkList) unknowns.add(nameWhere(c.get("Name") + " (CHECK - common words: " + c.get("Strings") + ")", (String) c.get("Where")));
        for (Map<String, Object> u : unknownList) unknowns.add(nameWhere((String) u.get("Name"), (String) u.get("Where")));

        Map<String, Object> mods = new LinkedHashMap<>();
        mods.put("Folders", folderLabels);
        mods.put("Verified", totalVerified);
        mods.put("CurseForgeApp", totalCf);
        mods.put("Unknown", totalUnknown + totalCheck);
        mods.put("Cheat", totalCheat);
        mods.put("Cheats", cheatList);
        mods.put("All", allList);
        mods.put("Unknowns", unknowns);
        report.mods = mods;
    }

    private void printGroups(String title, String tag, List<Map<String, Object>> list, Out.C tagColor, Out.C textColor) {
        if (list.isEmpty()) return;
        out.line();
        out.section(title + " (" + list.size() + ")");
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> c : list) groups.computeIfAbsent((String) c.get("Where"), k -> new ArrayList<>()).add(c);
        for (Map.Entry<String, List<Map<String, Object>>> g : groups.entrySet()) {
            out.line(Out.C.WHITE, "  " + g.getKey());
            for (Map<String, Object> c : g.getValue()) {
                out.text(tagColor, "    " + tag + " " + c.get("Name") + "  ");
                out.line(textColor, (String) c.get("Strings"));
            }
        }
    }

    private static long fileSize(Path p) {
        try { return Files.size(p); } catch (IOException e) { return -1; }
    }

    private static String sha1(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
