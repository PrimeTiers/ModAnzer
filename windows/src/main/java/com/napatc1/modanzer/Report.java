package com.napatc1.modanzer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** The results of one run, and sending them to staff. */
final class Report {
    static final String SERVER_NAME = "PrimeTiers";
    static final String URL = "https://reumqqmnuvabnpmbstnu.supabase.co/functions/v1/modanzer-report";

    Map<String, Object> mods;
    Map<String, Object> system;
    Map<String, Object> injection;
    List<String> accounts = new ArrayList<>();

    boolean isEmpty() { return mods == null && system == null && injection == null; }

    private static String modeName(String mode) {
        return switch (mode) {
            case "mods" -> "Mod Analyzer";
            case "injection" -> "Injection Checker";
            case "system" -> "System Check";
            default -> "Full Screenshare";
        };
    }

    /** The report as JSON, with the Windows user folders and user name hidden. */
    String toJson(Options opt, List<String> accountsFallback) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tool", "ModAnzer");
        r.put("mode", modeName(opt.mode));
        r.put("finished", ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx")));
        r.put("accounts", accounts.isEmpty() ? accountsFallback : accounts);
        r.put("mods", mods);
        r.put("system", system);
        r.put("injection", injection);
        return removeUserInfo(Json.write(r));
    }

    static String removeUserInfo(String json) {
        String[][] vars = { { Sys.userHome(), "~" }, { Sys.localAppData(), "%LOCALAPPDATA%" }, { Sys.appData(), "%APPDATA%" } };
        for (String[] v : vars) {
            if (v[0].isEmpty()) continue;
            json = json.replace(Json.write(v[0]).replaceAll("^\"|\"$", ""), v[1]);
        }
        // any other "\Users\<name>" left in a path (other drives, other accounts)
        return Pattern.compile("(?i)(\\\\\\\\Users\\\\\\\\)[^\\\\\"]+").matcher(json).replaceAll("$1<user>");
    }

    /** Sends the report and returns the reference code. Throws an IOException with a readable message. */
    static String send(String reportJson) throws java.io.IOException, InterruptedException {
        String resp = Sys.postJson(URL, "{\"report\":" + reportJson + "}", 30);
        Map<String, Object> o = Json.obj(Json.parse(resp));
        if (o == null || !(o.get("code") instanceof String c) || c.isEmpty()) throw new java.io.IOException("the server sent no code");
        return c;
    }

    static void writeFile(String file, String json) throws java.io.IOException {
        Files.writeString(Path.of(file), json, StandardCharsets.UTF_8);
    }
}
