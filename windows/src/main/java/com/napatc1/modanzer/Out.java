package com.napatc1.modanzer;

/** Where the scanner writes what it finds: the window, or the console. */
interface Out {
    enum C { TEXT, WHITE, GRAY, DIM, RED, YELLOW, DARKYELLOW, GREEN, CYAN, MAGENTA, SECTION }

    /** Text without a line break. */
    void text(C color, String text);

    /** A line of text. */
    default void line(C color, String text) { text(color, text + "\n"); }

    default void line() { text(C.TEXT, "\n"); }

    /** A progress message that the next one replaces. An empty message clears it. */
    default void progress(String message) {}

    /** A number for the window's boxes: verified, unknown, check, cheat or suspicious. */
    default void stat(String key, int value) {}

    default void section(String title) { line(C.SECTION, "{ " + title + " }"); }
}
