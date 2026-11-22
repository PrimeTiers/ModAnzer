package com.napatc1.modanzer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Small helpers: environment, running commands, web requests. */
final class Sys {
    private Sys() {}

    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    static final String USER_AGENT = "ModAnzer (github.com/napatc1/ModAnzer)";

    static String env(String name) {
        String v = System.getenv(name);
        return v == null ? "" : v;
    }

    static String appData() { return env("APPDATA"); }
    static String localAppData() { return env("LOCALAPPDATA"); }
    static String userHome() { String p = env("USERPROFILE"); return p.isEmpty() ? System.getProperty("user.home", "") : p; }

    /** Runs a command and returns its output, or null when it could not run or took too long. */
    static String run(int timeoutSeconds, String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            Thread t = new Thread(() -> {
                try (InputStream in = p.getInputStream()) { in.transferTo(bo); } catch (IOException ignored) { }
            });
            t.setDaemon(true);
            t.start();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            t.join(2000);
            return bo.toString(oemCharset());
        } catch (Exception e) {
            return null;
        }
    }

    /** Output of a PowerShell one-liner (Windows PowerShell ships with Windows). */
    static String powershell(int timeoutSeconds, String script) {
        return run(timeoutSeconds, "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command",
                "[Console]::OutputEncoding=[Text.Encoding]::UTF8; " + script);
    }

    private static Charset oemCharset() {
        String enc = System.getProperty("native.encoding");
        try { return enc != null ? Charset.forName(enc) : Charset.defaultCharset(); } catch (Exception e) { return StandardCharsets.UTF_8; }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();

    static String get(String url, int seconds) throws IOException, InterruptedException {
        HttpRequest rq = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(seconds)).header("User-Agent", USER_AGENT).GET().build();
        HttpResponse<String> r = HTTP.send(rq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode());
        return r.body();
    }

    /** POSTs JSON. Throws an IOException with the server's own error message when it sends one. */
    static String postJson(String url, String json, int seconds) throws IOException, InterruptedException {
        HttpRequest rq = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(seconds))
                .header("User-Agent", USER_AGENT).header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
        HttpResponse<String> r = HTTP.send(rq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() / 100 != 2) {
            String msg = "HTTP " + r.statusCode();
            java.util.Map<String, Object> o = Json.obj(Json.parse(r.body()));
            if (o != null && o.get("error") instanceof String e) msg = e;
            throw new IOException(msg);
        }
        return r.body();
    }

    static boolean exists(String p) { return p != null && !p.isEmpty() && Files.exists(Path.of(p)); }
}
