package com.napatc1.modanzer;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * ModAnzer: Minecraft screenshare scanner (read-only).
 * No arguments opens the window. --cli runs in the console:
 *   --mode mods|injection|system|full   --path FOLDER   --launcher NAME   --offline
 *   --live-seconds N   --no-send   --report-out FILE
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        Options opt = new Options();
        boolean cli = false;
        boolean modeGiven = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            String next = i + 1 < args.length ? args[i + 1] : null;
            switch (a) {
                case "--cli" -> cli = true;
                case "--offline" -> opt.offline = true;
                case "--no-send" -> opt.noSend = true;
                case "--mode" -> { if (next != null && List.of("mods", "injection", "system", "full").contains(next)) { opt.mode = next; modeGiven = true; } i++; }
                case "--path" -> { if (next != null) opt.path.add(next); i++; }
                case "--launcher" -> { if (next != null) opt.launcher = next; i++; }
                case "--live-seconds" -> { if (next != null) opt.liveSeconds = Integer.parseInt(next); i++; }
                case "--report-out" -> { if (next != null) opt.reportOut = next; i++; }
                default -> { }
            }
        }
        if (cli) { System.exit(runConsole(opt)); return; }
        final String initial = modeGiven ? opt.mode : null;
        javax.swing.SwingUtilities.invokeLater(() -> new Gui(initial).setVisible(true));
    }

    private static int runConsole(Options opt) {
        PrintStream stdout = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        Out out = new Out() {
            @Override public void text(C color, String text) { stdout.print(text); }
            @Override public void progress(String m) { if (!m.isEmpty()) System.err.print("\r" + m + "   "); else System.err.print("\r" + " ".repeat(70) + "\r"); }
        };
        out.text(Out.C.WHITE, "ModAnzer");
        out.line(Out.C.DIM, "  - Minecraft screenshare tool");
        out.line();
        Report report = new Report();
        Scan.run(out, opt, report);
        if (report.isEmpty()) return 0;
        String json = Scan.reportJson(opt, report);
        try {
            if (!opt.reportOut.isEmpty()) {
                Report.writeFile(opt.reportOut, json);
            } else if (!opt.noSend) {
                out.line();
                out.line(Out.C.WHITE, "Sending the results to " + Report.SERVER_NAME + " staff...");
                String code = Report.send(json);
                out.line(Out.C.GREEN, "Your code: " + code);
                out.line(Out.C.GRAY, "Give this code to " + Report.SERVER_NAME + " staff. Only staff can see your results with it.");
            }
        } catch (Exception e) {
            out.line(Out.C.RED, "Could not send the results: " + e.getMessage());
            out.line(Out.C.DIM, "Take a screenshot of this window for staff instead.");
        }
        return 0;
    }

    static String describe(String[] a) { return Arrays.toString(a); }
}
