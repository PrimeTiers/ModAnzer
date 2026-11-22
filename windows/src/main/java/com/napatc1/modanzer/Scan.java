package com.napatc1.modanzer;

import java.util.ArrayList;

/** Runs the chosen checks one after another. */
final class Scan {
    private Scan() {}

    static void run(Out out, Options opt, Report report) {
        try {
            switch (opt.mode) {
                case "injection" -> new Injection(out, opt, report).run();
                case "system" -> new SystemCheck(out, opt, report).run();
                case "full" -> {
                    new ModAnalyzer(out, opt, report).run();
                    out.line();
                    out.line(Out.C.DIM, "=".repeat(70));
                    out.line();
                    new SystemCheck(out, opt, report).run();
                    out.line(Out.C.DIM, "=".repeat(70));
                    out.line();
                    new Injection(out, opt, report).run();
                }
                default -> new ModAnalyzer(out, opt, report).run();
            }
        } catch (RuntimeException | Error e) {
            out.progress("");
            out.line(Out.C.RED, "ModAnzer stopped because of an error: " + e);
            StackTraceElement[] st = e.getStackTrace();
            if (st.length > 0) out.line(Out.C.DIM, "(" + st[0] + ")");
        }
        out.progress("");
    }

    /** The report as JSON, ready to send. Includes the Minecraft account names found on this PC. */
    static String reportJson(Options opt, Report report) {
        java.util.List<String> fallback = new ArrayList<>();
        if (report.accounts.isEmpty()) {
            try { fallback.addAll(SystemCheck.minecraftAccountNames()); } catch (RuntimeException ignored) { }
        }
        return report.toJson(opt, fallback);
    }
}
