package com.napatc1.modanzer;

import java.util.ArrayList;
import java.util.List;

/** What to scan, as chosen in the window or on the command line. */
final class Options {
    String mode = "mods";            // mods, injection, system or full
    List<String> path = new ArrayList<>(); // folders to scan instead of auto-detection
    boolean offline;
    boolean noSend;
    String launcher = "all";         // "all", or part of a launcher's name
    int liveSeconds;                 // injection checker: stop the live watch after this long (0 = until stopped)
    String reportOut = "";           // write the report here instead of sending it
    volatile boolean stop;           // set to end the live watch the normal way, so the summary still prints
}
