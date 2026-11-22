package com.napatc1.modanzer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Cheat names, and the search through a jar for them. */
final class Cheats {
    private Cheats() {}

    /** Strong names: unusual words that are basically only used by cheats. Matched case-sensitively anywhere. */
    static final String[] STRONG = {
        "AnchorTweaks", "AutoAnchor", "AutoCrystal", "AutoDoubleHand", "AutoHitCrystal",
        "AutoPot", "AutoTotem", "InventoryTotem", "JumpReset", "LegitTotem", "PingSpoof",
        "SelfDestruct", "ShieldBreaker", "TriggerBot", "AxeSpam", "WebMacro",
        "KillAura", "CrystalAura", "AnchorAura", "AutoClicker"
    };

    /**
     * Weak names: also ordinary game-code words (getVelocity, renderHitboxes...). They only count when they
     * are a whole name on their own: a class called Velocity.class / VelocityModule.class, or the exact text
     * "Velocity" stored as its own string in a class file.
     */
    static final String[] WEAK = { "Velocity", "Hitboxes", "AimAssist", "AutoArmor", "FastPlace" };

    private static String alt(String[] names) {
        return Arrays.stream(names).map(Pattern::quote).collect(Collectors.joining("|"));
    }

    static final Pattern STRONG_RE = Pattern.compile(alt(STRONG));
    /** A class named exactly like a cheat module, e.g. AutoTotem or TriggerBotModule. */
    static final Pattern CLASS_RE = Pattern.compile("^(?:" + alt(STRONG) + ")(?:Module|Hack|Feature|Mod)?$");
    static final Pattern WEAK_NAME_RE = Pattern.compile("(?:^|/)(" + alt(WEAK) + ")(?:Module|Hack|Feature|Mod)?(?:\\$[^/]*)?\\.class$");
    /** CONSTANT_Utf8 entry in a class file: tag 0x01, 2-byte length, then the text. */
    static final Pattern WEAK_CONST_RE;
    static {
        StringBuilder sb = new StringBuilder("\u0001\u0000(?:");
        for (int i = 0; i < WEAK.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(Pattern.quote(String.valueOf((char) WEAK[i].length()) + WEAK[i]));
        }
        sb.append(")(?![\\w])");
        WEAK_CONST_RE = Pattern.compile(sb.toString());
    }

    /** A known mod whose name says what it does counts as a cheat. */
    static final Pattern FLAGGED_NAME_RE = Pattern.compile(
        "(?ix) \\bmacros?\\b | auto[\\s_-]?(totem|crystal|anchor|pot|clicker|click|hit|double[\\s_-]?hand|shield[\\s_-]?break)"
        + " | trigger[\\s_-]?bot | (crystal|anchor|kill)[\\s_-]?aura | aim[\\s_-]?(assist|bot|lock)"
        + " | (inventory|legit|hover)[\\s_-]?totem | jump[\\s_-]?reset | ping[\\s_-]?spoof | axe[\\s_-]?spam"
        + " | web[\\s_-]?macro | self[\\s_-]?destruct");

    /** Names to look for in Minecraft's memory. */
    static final String[] MEMORY_NEEDLES;
    static {
        List<String> l = new ArrayList<>(Arrays.asList(STRONG));
        l.addAll(Arrays.asList("meteordevelopment", "wurstclient", "net/ccbluex", "aristois", "rusherhack", "thunderhack", "impactclient", "doomsdayclient"));
        MEMORY_NEEDLES = l.toArray(new String[0]);
    }

    /** Legit client files that contain common words like "Velocity" for their own features. */
    record KnownClient(String name, Pattern file, Pattern folder) {}
    static final KnownClient[] KNOWN_CLIENTS = {
        new KnownClient("Dawn client", Pattern.compile("^dawn-client.*\\.jar$"), Pattern.compile("[\\\\/]\\.(dawn|feather)[\\\\/]"))
    };

    static String knownClient(String fileName, String fullPath) {
        for (KnownClient k : KNOWN_CLIENTS) {
            if (k.file().matcher(fileName).find() && k.folder().matcher(fullPath).find()) return k.name();
        }
        return null;
    }

    static final int MAX_ENTRY_BYTES = 8 * 1024 * 1024;

    /** One place in a jar where cheat names were found. */
    record Hit(String where, List<String> strings, boolean strong) {}

    /** Searches a jar for cheat strings: entry names, then file contents, then nested jars. */
    static void searchJar(byte[] jar, String label, List<Hit> hits, int level) {
        TreeSet<String> found = new TreeSet<>();
        TreeSet<String> weak = new TreeSet<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                Matcher m = STRONG_RE.matcher(name);
                while (m.find()) found.add(m.group());
                Matcher wm = WEAK_NAME_RE.matcher(name);
                if (wm.find()) weak.add(wm.group(1));

                String lower = name.toLowerCase();
                boolean nested = lower.endsWith(".jar") && level < 3;
                boolean text = lower.endsWith(".class") || lower.endsWith(".json") || lower.endsWith(".toml") || lower.endsWith(".mf");
                if (!nested && !text) continue;
                byte[] data = readLimited(zin);
                if (data == null || data.length == 0) continue;
                if (nested) {
                    searchJar(data, label + " -> " + name.substring(name.lastIndexOf('/') + 1), hits, level + 1);
                    continue;
                }
                String s = new String(data, StandardCharsets.ISO_8859_1);
                Matcher sm = STRONG_RE.matcher(s);
                while (sm.find()) found.add(sm.group());
                if (lower.endsWith(".class")) {
                    Matcher cm = WEAK_CONST_RE.matcher(s);
                    while (cm.find()) weak.add(cm.group().substring(3));
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // not a readable zip: nothing to search
        }
        if (!found.isEmpty() || !weak.isEmpty()) {
            TreeSet<String> all = new TreeSet<>(found);
            all.addAll(weak);
            hits.add(new Hit(label, new ArrayList<>(all), !found.isEmpty()));
        }
    }

    /** Reads the current zip entry, or null when it is bigger than the limit. */
    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > MAX_ENTRY_BYTES) return null;
        }
        return bo.toByteArray();
    }

    /** Class names only (no unpacking of contents), for jars of mods that are already recognised. */
    static List<String> cheatClasses(byte[] jar) {
        TreeSet<String> found = new TreeSet<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String n = e.getName();
                if (!n.endsWith(".class")) continue;
                String leaf = n.substring(n.lastIndexOf('/') + 1, n.length() - 6);
                int d = leaf.indexOf('$');
                if (d >= 0) leaf = leaf.substring(0, d);
                if (CLASS_RE.matcher(leaf).matches()) found.add(leaf);
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return new ArrayList<>(found);
    }

    /** CurseForge fingerprint: MurmurHash2 (seed 1) of the file with tab, LF, CR and space bytes removed. */
    static long curseForgeFingerprint(byte[] raw) {
        byte[] data = new byte[raw.length];
        int len = 0;
        for (byte b : raw) if (b != 9 && b != 10 && b != 13 && b != 32) data[len++] = b;
        final int m = 0x5bd1e995;
        int h = 1 ^ len;
        int i = 0;
        while (len - i >= 4) {
            int k = (data[i] & 0xff) | (data[i + 1] & 0xff) << 8 | (data[i + 2] & 0xff) << 16 | (data[i + 3] & 0xff) << 24;
            k *= m;
            k ^= k >>> 24;
            k *= m;
            h = h * m ^ k;
            i += 4;
        }
        int rest = len - i;
        if (rest >= 3) h ^= (data[i + 2] & 0xff) << 16;
        if (rest >= 2) h ^= (data[i + 1] & 0xff) << 8;
        if (rest >= 1) { h ^= data[i] & 0xff; h *= m; }
        h ^= h >>> 13;
        h *= m;
        h ^= h >>> 15;
        return h & 0xffffffffL;
    }
}
